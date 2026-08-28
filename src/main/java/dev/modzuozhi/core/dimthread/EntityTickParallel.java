package dev.modzuozhi.core.dimthread;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * 实体 tick 细粒度并行的上下文管理（借鉴 MCMT/Async 思路，写我们自己的实现）。
 * <p>
 * 实体 tick 并行与方块实体/区块环境并行的关键区别：{@code ServerLevel.tick} 里对每个实体
 * 的 {@code guardEntityTick(...)} 调用位于 {@code entityTickList.forEach(...)} 的 lambda 内部，
 * 无法像 TE/区块那样在循环体内 {@code @Redirect}。因此这里用 <b>ThreadLocal 屏障</b>在
 * {@code ServerLevelEntityTickMixin} 的 {@code forEach} 前后建立/收口，再由
 * {@code EntityTickParallelMixin} 在 {@code guardEntityTick} 处读取当前屏障并提交并行。
 * <p>
 * <b>第14步（对齐 Async 零锁路线）</b>：实体移动、生成、移除的共享状态（{@code EntityLookup}、
 * {@code knownUuids}、{@code chunkVisibility}、section 存储）已全部并发化，不再需要全局锁
 * {@link #SECTION_LOCK} 保护。实体生命周期写入现在无锁并行，碰撞查询也走并行。
 * 仅保留 {@code EntitySection} 内容（{@code ClassInstanceMultiMap}）的分片读写锁保护内容遍历
 * 与增删的互斥（Async 未去掉分片锁，保留为安全边界；分片竞争在 256 片下极低，不是瓶颈）。
 */
public final class EntityTickParallel {
    /** 当前维度 worker 线程正在并行 tick 的实体屏障（仅 worker 线程非 null）。 */
    private static final ThreadLocal<FineGrainScheduler.Barrier> CURRENT = new ThreadLocal<>();

    /** 本 tick 收集的待并行实体批（仅并行窗口 worker 线程非 null）。 */
    private static final ThreadLocal<Batch> BATCH = new ThreadLocal<>();

    /** 分片锁数量（2 的幂）。越大锁竞争越小，内存开销约 256×对象头，可忽略。 */
    private static final int STRIPE_COUNT = 256;
    /** 分片读写锁数组：保护单个 {@code EntitySection} 的内容读写（{@code ClassInstanceMultiMap}）。 */
    private static final ReentrantReadWriteLock[] SECTION_LOCKS = new ReentrantReadWriteLock[STRIPE_COUNT];

    /**
     * 当前线程的锁配对栈：记录每次 lock 调用时<b>是否真正加了锁</b>，unlock 时弹出对应决定。
     * <p>
     * 背景：{@link #isLockDisabled()} 在「细粒度关闭且无残留子任务」时为 true（零锁纯串行）。
     * 但 {@code /dimensionalripper fine on|off} 运行期切换瞬间，该判定会在一次 lock→unlock 之间翻转：
     * lock 时跳过加锁、unlock 时却执行解锁 → 对未持有的锁 unlock，抛
     * {@code IllegalMonitorStateException: attempt to unlock read lock}（实测：大量村民 tick 时
     * 执行 {@code /dimensionalripper fine on} 触发）。用本栈把 lock 时的决定记录下来，unlock 严格按
     * 记录配对（支持嵌套，LIFO），无论 {@code isLockDisabled()} 是否翻转都保持一致。
     */
    private static final ThreadLocal<Deque<Boolean>> LOCK_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    static {
        for (int i = 0; i < STRIPE_COUNT; i++) {
            SECTION_LOCKS[i] = new ReentrantReadWriteLock();
        }
    }

    private EntityTickParallel() {
    }

    /**
     * 将 section key（{@code SectionPos.asLong(x,y,z)}）映射到分片索引。
     * 使用乘散列取高位，保证相邻 section 均匀分布到不同分片。
     */
    private static int stripe(long sectionKey) {
        long hash = sectionKey * 0x9E3779B97F4A7C15L;
        return (int) (hash >>> 32) & (STRIPE_COUNT - 1);
    }

    /** 对指定 section 分片加<b>写</b>锁（内容增删，独占）；记录配对决定。 */
    public static void lockSection(long sectionKey) {
        boolean locked = !isLockDisabled();
        LOCK_STACK.get().push(locked);
        if (locked) {
            SECTION_LOCKS[stripe(sectionKey)].writeLock().lock();
        }
    }

    /** 对指定 section 分片解<b>写</b>锁；严格按 lock 时的配对决定解锁。 */
    public static void unlockSection(long sectionKey) {
        if (popLocked()) {
            SECTION_LOCKS[stripe(sectionKey)].writeLock().unlock();
        }
    }

    /** 对指定 section 分片加<b>读</b>锁（内容遍历/查询，共享）；记录配对决定。 */
    public static void lockSectionRead(long sectionKey) {
        boolean locked = !isLockDisabled();
        LOCK_STACK.get().push(locked);
        if (locked) {
            SECTION_LOCKS[stripe(sectionKey)].readLock().lock();
        }
    }

    /** 对指定 section 分片解<b>读</b>锁；严格按 lock 时的配对决定解锁。 */
    public static void unlockSectionRead(long sectionKey) {
        if (popLocked()) {
            SECTION_LOCKS[stripe(sectionKey)].readLock().unlock();
        }
    }

    /** 弹出本线程最近一次 lock 的配对决定（LIFO）；栈空视为未加锁（防御）。 */
    private static boolean popLocked() {
        Deque<Boolean> stack = LOCK_STACK.get();
        Boolean locked = stack.pollFirst();
        if (stack.isEmpty()) {
            LOCK_STACK.remove();
        }
        return Boolean.TRUE.equals(locked);
    }

    /**
     * 是否应跳过锁（零开销纯串行）。
     * <p>
     * 锁的唯一目的是保护<b>并行子任务</b>对 {@code EntitySection} 的并发增删。而子任务是否
     * 并行由 {@link FineGrainScheduler#isEnabled()} 决定（{@code submitBatch} 关闭时同步串行）。
     * 因此锁的启用必须与细粒度并行开关<b>一致</b>：若这里跟随全局 {@code ModZuozhi.ENABLED}，
     * 会出现"全局 off → 锁被跳过，但细粒度仍开 → 子任务并行增删而主线程无锁遍历"的
     * {@code ConcurrentModificationException} 竞态（已实测崩溃）。
     * <p>
     * <b>残留保护</b>：关闭细粒度（{@code /dimensionalripper fine off}、{@code /dimensionalripper off}、
     * FaultGuard 降级）瞬间，{@code SUB_POOL} 里可能仍有<b>已提交未跑完</b>的实体子任务；
     * 它们与退化为串行的维度 tick / 主线程并发改 {@code EntitySection}。因此只要还存在残留
     * 子任务（{@link FineGrainScheduler#activeSubTasks()} &gt; 0），锁就必须保持启用，直到
     * {@code awaitIdle} 把它们全部排空后才回到零锁纯串行。
     * <p>
     * 注意：本判定可能随时间变化（{@code fine on|off} 切换）。lock/unlock 的<b>配对一致性</b>
     * 由 {@link #LOCK_STACK} 保证——lock 时快照本判定，unlock 严格按快照执行，不会因判定
     * 在两次调用间翻转而对未持有的锁 unlock。
     */
    public static boolean isLockDisabled() {
        return !FineGrainScheduler.isEnabled() && FineGrainScheduler.activeSubTasks() == 0;
    }

    /** 当前线程的实体屏障（worker 端）；无则 null。 */
    public static FineGrainScheduler.Barrier current() {
        return CURRENT.get();
    }

    /**
     * 本 tick 收集的一批待并行实体（同一 consumer，即 {@code tickNonPassenger}）。
     * 收集代替逐实体提交：循环结束后按<b>实体所在区块分组</b>统一提交（同区块实体一个
     * 子任务串行 tick，跨区块并行），把每实体一次的调度/屏障计数开销从 O(实体数)
     * 降到 O(区块数)，并把实体并行的互斥粒度细化到「区块」（问题1 架构项）。
     */
    public static final class Batch {
        public final MinecraftServer server;
        public final Consumer<Entity> consumer;
        /** 按实体所在区块分组：chunkKey → 同区块实体列表（同区块串行 tick，跨区块并行）。 */
        public final Map<Long, List<Entity>> byChunk = new HashMap<>();

        public Batch(MinecraftServer server, Consumer<Entity> consumer) {
            this.server = server;
            this.consumer = consumer;
        }
    }

    /** 收集一个待并行 tick 的实体（在 {@code guardEntityTick} 中调用，替代逐实体提交）。 */
    public static void collect(MinecraftServer server, Consumer<Entity> consumer, Entity entity) {
        Batch batch = BATCH.get();
        if (batch == null) {
            batch = new Batch(server, consumer);
            BATCH.set(batch);
        }
        long chunkKey = ChunkPos.asLong(entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
        batch.byChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entity);
    }

    /** 实体循环开始：为本维度 tick 建立实体子任务屏障（仅 worker 线程调用）。 */
    public static void begin() {
        CURRENT.set(FineGrainScheduler.beginBarrier());
    }

    /**
     * 实体循环结束（finally 中调用）：先把收集的实体批按块提交并行，再等待全部子任务完成
     * 并执行延迟回调，清除上下文。
     */
    public static void end() {
        FineGrainScheduler.Barrier barrier = CURRENT.get();
        if (barrier == null) {
            return;
        }
        CURRENT.remove();
        Batch batch = BATCH.get();
        BATCH.remove();
        if (batch != null && !batch.byChunk.isEmpty()) {
            barrier.submitByChunk(batch.server, batch.byChunk, batch.consumer);
        }
        barrier.await();
        barrier.drain();
    }
}
