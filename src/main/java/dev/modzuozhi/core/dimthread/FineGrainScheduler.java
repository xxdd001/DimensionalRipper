package dev.modzuozhi.core.dimthread;

import dev.modzuozhi.ModZuozhi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static dev.modzuozhi.core.dimthread.DimThreadCore.MOD_ID;

/**
 * 细粒度并行调度器（借鉴 MCMT 思路，写我们自己的实现）。
 * <p>
 * 在现有 dimthreads 式「维度级并行」基础上，提供<b>维度内更细粒度并行</b>的能力：
 * 维度 worker 在本维度的 tick 内部，把可并行的子单元（方块实体、区块环境、白名单实体）
 * 提交到<b>独立的细粒度子任务池</b>并行执行，再用本维度自己的屏障 {@link Barrier} 收口，
 * 保持该维度 tick 内部的顺序边界，同时兼容外层主线程的 {@code awaitCompletion()} 收口。
 * <p>
 * 设计要点：
 * <ul>
 *     <li><b>独立线程池</b>：子任务使用 {@link #SUB_POOL} 专用池，与维度 worker 池解耦。
 *         否则"维度 worker 阻塞 await 等待子任务"会占满维度池线程，导致子任务永远无人执行
 *         而死锁（这也是之前必现死锁的根因）。独立池固定 CPU 线程，从结构上不可能死锁。</li>
 *     <li><b>独立开关</b>：{@link #isEnabled()} 默认关闭（保守）。关闭时 {@link Barrier#submit}
 *         直接同步执行，行为完全退化为串行，绝不引入并发风险；由 {@code /dimensionalripper fine on|off}
 *         运行时切换。</li>
 *     <li><b>异常隔离</b>：子任务异常只记录日志，不中断本维度 tick，也不污染外层维度收口。</li>
 * </ul>
 */
public final class FineGrainScheduler {

    /**
     * 细粒度并行总开关（默认开启，已长时间测试稳定；出错时 {@link FaultGuard} 会自动降级关闭）。
     * 可由 {@code /dimensionalripper fine on|off} 运行时切换。
     */
    private static volatile boolean enabled = true;

    /** 细粒度子任务的独立线程池（daemon，固定 CPU 线程数）。 */
    private static final ThreadPoolExecutor SUB_POOL = new ThreadPoolExecutor(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName(MOD_ID + "_subtask");
        return t;
    });

    /** 当前并行子任务的延迟执行队列（仅并行子任务线程内非 null）。 */
    private static final ThreadLocal<PostExecuteQueue> CURRENT_QUEUE = new ThreadLocal<>();

    /**
     * 正在 {@link #SUB_POOL} 中运行或排队的子任务总数（含残留排空期）。
     * <p>
     * 用于两点：
     * <ol>
     *     <li>{@link #awaitIdle} 判断是否排空（比 {@code getActiveCount()+queue} 更准，覆盖排队任务）；</li>
     *     <li>{@link EntityTickParallel#isLockDisabled} 在「细粒度已关但仍有残留子任务」期间保持
     *         分片锁启用，保证残留子任务与退化为串行的维度 tick 并发改 {@code EntitySection} 时仍互斥，
     *         否则 `fine off`/`off` 瞬间锁被禁用 + 残留子任务无锁运行 → CME（已实测）。</li>
     * </ol>
     */
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE_SUBTASKS =
            new java.util.concurrent.atomic.AtomicInteger();

    private FineGrainScheduler() {
    }

    /** 当前仍在运行/排队的子任务数（关闭细粒度后的残留排空期也计入）。 */
    public static int activeSubTasks() {
        return ACTIVE_SUBTASKS.get();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        // 子任务使用独立 SUB_POOL，无需再扩容维度线程池；只需让线程数目标在下次 tick 重算。
        DimThreadCore.MANAGER.invalidateThreadCount();
        ModZuozhi.LOGGER.info("[FineGrain] 细粒度并行已切换为 {}", value ? "开启" : "关闭");
    }

    /** 是否处于某个并行子任务上下文（用于拦截跨线程的 Level 结构写入）。 */
    public static boolean inSubTask() {
        return CURRENT_QUEUE.get() != null;
    }

    /**
     * 等待已提交的细粒度子任务全部完成（全局关闭时调用，带超时兜底）。
     * <p>
     * 目的：{@code /dimensionalripper off} 或自动降级后，{@link #SUB_POOL} 里可能残留上一拍提交、
     * 尚未跑完的实体子任务；此时主线程已退回原版串行 tick 同一批实体，会与残留子任务
     * <b>并发 tick 同一实体</b>导致异常（表现为"关闭时反复出现子任务异常"）。先排空
     * SUB_POOL 再让主线程接管，消除该竞态。超时则强制继续，避免异常场景下永久阻塞。
     */
    public static void awaitIdle(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (ACTIVE_SUBTASKS.get() == 0) {
                return;
            }
            LockSupport.parkNanos(100_000L); // 0.1ms 退避
        }
        ModZuozhi.LOGGER.warn("[FineGrain] 等待子任务排空超时（>{}ms），强制继续", timeoutMs);
    }

    /** 当前并行子任务的延迟执行队列；非子任务上下文返回 null。 */
    public static PostExecuteQueue currentQueue() {
        return CURRENT_QUEUE.get();
    }

    /** 为本维度 tick 创建一个新的子任务屏障。 */
    public static Barrier beginBarrier() {
        return new Barrier();
    }

    /**
     * 维度内子任务屏障：提交多个可并行子任务，{@link #await()} 等待全部完成。
     * <p>
     * 仅在 {@link FineGrainScheduler#isEnabled()} 为 true 时真正并行；关闭时
     * {@link #submit} 直接在当前线程同步执行，保证开关无关的正确性。
     */
    public static final class Barrier {
        private final PostExecuteQueue postQueue = new PostExecuteQueue();
        private int pending;

        /**
         * 子任务屏障等待超时（毫秒）。一批子任务（方块实体/区块/实体）正常应在几十 ms 内完成，
         * 超时意味着某个子任务卡死（例如阻塞在另一个线程持有的 {@link ChunkLock} 或 section 锁上）。
         * 此时无条件 {@code latch.await()} 会让维度 worker 永久阻塞，进而死锁主线程保存流程。
         */
        private static final long AWAIT_TIMEOUT_MS = 10_000L;

        /**
         * 提交一个细粒度子任务。
         *
         * @param server 当前服务器（用于取线程池）
         * @param task   子任务（如某个方块实体/实体的 tick）
         */
        public void submit(MinecraftServer server, Runnable task) {
            submit(server, task, null);
        }

        /**
         * 提交一个细粒度子任务，可选地锁定其所在区块（防止同一区块内并发访问）。
         *
         * @param server 当前服务器（保留参数，当前子任务使用独立线程池）
         * @param task   子任务（如某个方块实体/实体的 tick）
         * @param pos    子任务所在世界坐标；非 null 时执行前锁定所在区块
         */
        public void submit(MinecraftServer server, Runnable task, BlockPos pos) {
            submit(server, task, pos, 0);
        }

        /**
         * 提交一个细粒度子任务，可选地锁定其所在区块及其周围 radius 圈区块。
         *
         * @param server 当前服务器（保留参数，当前子任务使用独立线程池）
         * @param task   子任务（如某个方块实体/实体的 tick）
         * @param pos    子任务所在世界坐标；非 null 时执行前锁定区块
         * @param radius 区块锁半径：0=仅本区块；&gt;0 时锁定 {@code (1+2r)²} 个区块
         *               （排序加锁防死锁），供跨区块访问的子任务使用
         */
        public void submit(MinecraftServer server, Runnable task, BlockPos pos, int radius) {
            if (!FineGrainScheduler.enabled) {
                task.run();
                return;
            }
            synchronized (this) {
                pending++;
            }
            ACTIVE_SUBTASKS.incrementAndGet();
            SUB_POOL.execute(() -> {
                AutoCloseable chunkLock = (pos == null)
                        ? null
                        : (radius > 0 ? ChunkLock.lock(pos, radius) : ChunkLock.lock(pos));
                PostExecuteQueue prev = CURRENT_QUEUE.get();
                CURRENT_QUEUE.set(postQueue);
                try {
                    task.run();
                } catch (Throwable t) {
                    ModZuozhi.LOGGER.error("[FineGrain] 子任务异常", t);
                    FaultGuard.onSubTaskFailure();
                } finally {
                    CURRENT_QUEUE.set(prev);
                    if (chunkLock != null) {
                        try {
                            chunkLock.close();
                        } catch (Exception ignore) {
                        }
                    }
                    synchronized (this) {
                        pending--;
                        if (pending == 0) {
                            this.notifyAll();
                        }
                    }
                    ACTIVE_SUBTASKS.decrementAndGet();
                }
            });
        }

        /**
         * 按实体所在区块分组提交待并行实体（问题1 架构项：实体并行的互斥粒度细化到「区块」）。
         * <p>
         * 原实现把实体列表按线程池大小切块，一个块可能包含<b>不同区块</b>的实体，它们在
         * 不同子任务线程并发 tick 时可能<b>并发写同一区块</b>的方块/实体容器（fastutil/ArrayList
         * 并发损坏）。这里改为：<b>同区块实体放入同一子任务串行 tick</b>（天然互斥写同一区块），
         * 不同区块的子任务并行执行；每个子任务持本区块 {@link ChunkLock}，与同区块的方块实体/
         * 区块环境子任务互斥。既消除「多 worker 同时写一个 chunk」，又保留跨区块的并行度。
         *
         * @param server   当前服务器
         * @param byChunk  按实体所在区块分组的映射（chunkKey → 同区块实体列表）
         * @param consumer 实体 tick 函数（如 {@code tickNonPassenger}）
         */
        public void submitByChunk(MinecraftServer server, Map<Long, List<Entity>> byChunk, Consumer<Entity> consumer) {
            if (byChunk.isEmpty()) {
                return;
            }
            if (!FineGrainScheduler.enabled) {
                // 细粒度关闭：当前线程逐实体串行 tick（行为等价原版）
                for (List<Entity> entities : byChunk.values()) {
                    for (Entity entity : entities) {
                        if (!entity.isRemoved()) {
                            consumer.accept(entity);
                        }
                    }
                }
                return;
            }
            synchronized (this) {
                pending += byChunk.size();
            }
            ACTIVE_SUBTASKS.addAndGet(byChunk.size());
            for (Map.Entry<Long, List<Entity>> entry : byChunk.entrySet()) {
                long chunkKey = entry.getKey();
                List<Entity> entities = entry.getValue();
                int chunkX = (int) (chunkKey >> 32);
                int chunkZ = (int) (chunkKey & 0xFFFFFFFFL);
                SUB_POOL.execute(() -> modzuozhi_runChunkByChunk(chunkX, chunkZ, entities, consumer));
            }
        }

        /** 单区块组子任务：持本区块锁，组内实体串行 tick，单实体异常隔离。 */
        private void modzuozhi_runChunkByChunk(int chunkX, int chunkZ, List<Entity> entities, Consumer<Entity> consumer) {
            PostExecuteQueue prev = CURRENT_QUEUE.get();
            CURRENT_QUEUE.set(postQueue);
            // 持本区块锁：与同区块的 TE/区块环境子任务互斥写同一区块的方块/实体容器
            try (AutoCloseable chunkLock = ChunkLock.lock(chunkX, chunkZ)) {
                for (Entity entity : entities) {
                    try {
                        if (!entity.isRemoved()) {
                            consumer.accept(entity);
                        }
                    } catch (Throwable t) {
                        ModZuozhi.LOGGER.error("[FineGrain] 子任务异常", t);
                        FaultGuard.onSubTaskFailure();
                    }
                }
            } catch (Exception ignore) {
                // 区块锁释放异常：忽略（不影响实体 tick）
            } finally {
                CURRENT_QUEUE.set(prev);
                synchronized (this) {
                    pending--;
                    if (pending == 0) {
                        this.notifyAll();
                    }
                }
                ACTIVE_SUBTASKS.decrementAndGet();
            }
        }

        /**
         * 阻塞等待本维度所有已提交子任务完成；超时触发 FaultGuard 降级，避免永久死锁。
         * <p>
         * 用 {@code synchronized + wait/notify}（而非一次性 {@code CountDownLatch}）：
         * 原实现里任务可能在「循环内边提交边完成」——前面任务先完成使 latch 永久归零，
         * 之后再提交的任务在 await 时无法被等待（CountDownLatch 归零后不能重开），导致
         * 子任务未完成 worker 就继续执行 → 竞态（高危场景：TE/区块环境循环逐任务提交）。
         * 改用 {@code while (pending > 0) wait()} 后，无论任务何时完成都会 {@code notifyAll}，
         * await 一直等到当前 pending 归零，语义正确且仍保留超时降级。
         */
        public void await() {
            synchronized (this) {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MS);
                while (pending > 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        ModZuozhi.LOGGER.error("[FineGrain] 子任务屏障等待超时（>{}ms），存在卡死的子任务，触发降级", AWAIT_TIMEOUT_MS);
                        FaultGuard.onBarrierTimeout();
                        return;
                    }
                    try {
                        // wait 最小粒度毫秒；剩余不足 1ms 也按 1ms 等待，由 while 复查
                        this.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        /** 维度 worker 收口处：串行执行子任务投递的延迟回调。 */
        public void drain() {
            postQueue.drain();
        }
    }
}
