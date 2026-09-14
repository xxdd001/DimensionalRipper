package dev.modzuozhi.core.dimthread;

import dev.modzuozhi.ModZuozhi;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * 每服务器激活状态与线程池管理（dimthreads 模型）。
 * <p>
 * 线程池按服务器懒创建（每个 {@code MinecraftServer} 实例一个），并在服务器停止时关闭清理。
 * <p>
 * 是否启用维度并行，由两个条件共同决定：全局开关 {@link ModZuozhi#isEnabled()}（可通过
 * {@code /dimensionalripper on|off} 运行时切换）+ 服务器级激活标记。任一为 false 即退回原版串行。
 */
public class ServerManager {
    /** CPU 核数是常量，仅在首个服务器创建线程池前计算一次。 */
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    /**
     * 使用 {@link ConcurrentHashMap}：其 {@code computeIfAbsent} 是原子的，保证同一
     * {@code MinecraftServer} 只会有一个线程池实例。若用 {@code synchronizedMap} 包装，
     * {@code computeIfAbsent} 默认实现非原子，维度 worker 并发调用 {@link #getThreadPool} 时会
     * 竞态创建出多个池实例，导致"子任务提交进池 A、主线程 awaitCompletion 却等在池 B"的错配死锁。
     */
    private final Map<MinecraftServer, Boolean> actives = new ConcurrentHashMap<>();
    public final Map<MinecraftServer, ThreadPool> threadPools = new ConcurrentHashMap<>();
    /** 缓存上次同步的维度数，维度数未变化时跳过遍历与线程调整，把每 tick 开销降到最低。 */
    private volatile int lastDimCount = -1;

    /** 每维度独立 tick 循环（维度隔离模型，见 {@link LevelTickLoop}）。key 为维度。 */
    private final Map<ServerLevel, LevelTickLoop> loops = new ConcurrentHashMap<>();
    /** 承载所有维度 loop 自排程的调度池（{@code schedule(task, nspt)} 需要 Scheduled 能力）。 */
    private volatile ScheduledThreadPoolExecutor masterPool;
    /** 玩家连接当前所属维度（key 为 Connection，value 为维度），供跨维度/登录时幂等迁移连接。 */
    private final Map<Connection, ServerLevel> connectionRoutes = new ConcurrentHashMap<>();

    public boolean isActive(MinecraftServer server) {
        // 每次调用都叠加全局开关，保证 /dimensionalripper off 立即退回原版
        return ModZuozhi.isEnabled() && this.actives.computeIfAbsent(server, s -> true);
    }

    public void setActive(MinecraftServer server, boolean value) {
        this.actives.put(server, value);
    }

    /** 是否处于单机暂停（ESC 菜单）状态。 */
    public boolean isPaused() {
        for (LevelTickLoop loop : this.loops.values()) {
            if (loop.isPaused()) {
                return true;
            }
        }
        return false;
    }

    public ThreadPool getThreadPool(MinecraftServer server) {
        // 初始尺寸取 CPU 核数兜底，实际线程数由 syncThreadCount 每 tick 贴合维度数
        return this.threadPools.computeIfAbsent(server, s -> {
            ModZuozhi.LOGGER.info("[DimThread] 新建维度线程池 server@{}", System.identityHashCode(s));
            return new ThreadPool(CPU_COUNT);
        });
    }

    /**
     * 回收不再属于当前活跃服务器的僵尸线程池。
     * <p>
     * 启动 / 世界加载阶段可能创建多个 {@code MinecraftServer} 实例，每个都会建一套维度线程池；
     * 旧的池不再被 {@code tickWorlds} 使用却永远占用线程（每维度一个 worker），造成线程数量失控。
     * 主线程 {@code tickWorlds} 每 tick 调用本方法，把不属于当前 {@code current} 的旧池 shutdown 并移除，
     * 使维度线程收敛回「每维度 1 个 worker」。
     */
    public void reapStalePools(MinecraftServer current) {
        this.threadPools.forEach((server, pool) -> {
            if (server != current) {
                pool.shutdown();
                this.threadPools.remove(server, pool);
            }
        });
    }

    /**
     * 让该服务器的线程池线程数贴合当前活跃维度数量（每维度 1 个 worker）。
     * <p>
     * 注意：细粒度子任务运行在 {@code FineGrainScheduler.SUB_POOL}（独立线程池），维度池
     * 只需承载「每维度 1 个 worker」的维度 tick，<b>无需</b>随 CPU 扩容。早期版本曾在此按
     * {@code fine} 开关把维度池扩容到 CPU 核数，但独立子任务池上线后该逻辑已冗余，会造成
     * 大量闲置的维度线程。统一收缩到维度数即可。
     * <p>
     * 目标值未变化时（绝大多数 tick）直接返回，不做遍历、不调整线程池，几乎零开销。
     */
    public void syncThreadCount(MinecraftServer server) {
        int dims = 0;
        for (var ignored : server.getAllLevels()) {
            dims++;
        }
        int target = Math.max(1, Math.min(dims, CPU_COUNT));
        if (target == this.lastDimCount) {
            return;
        }
        this.lastDimCount = target;
        ThreadPool pool = getThreadPool(server);
        pool.setThreadCount(target);
    }

    /** 使线程数缓存失效，下次 tick 强制重算（开关切换时调用）。 */
    public void invalidateThreadCount() {
        this.lastDimCount = -1;
    }

    /**
     * 全局调度池（承载所有维度 loop 的自排程）。懒创建，线程为 daemon，避免阻止服务器停机。
     * 线程数取 CPU 核数，保证每个活跃维度 loop 都有独立线程跑、不互相拖累。
     */
    public ScheduledThreadPoolExecutor getMasterPool() {
        ScheduledThreadPoolExecutor pool = this.masterPool;
        if (pool == null || pool.isShutdown()) {
            synchronized (this) {
                pool = this.masterPool;
                if (pool == null || pool.isShutdown()) {
                    AtomicInteger seq = new AtomicInteger();
                    pool = new ScheduledThreadPoolExecutor(CPU_COUNT, r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName(DimThreadCore.MOD_ID + "_server_dimloop_" + seq.getAndIncrement());
                        return t;
                    });
                    pool.setRemoveOnCancelPolicy(true);
                    this.masterPool = pool;
                }
            }
        }
        return pool;
    }

    /** 获取（懒创建）某维度的独立 tick 循环。 */
    public LevelTickLoop getLoop(ServerLevel level) {
        return this.loops.computeIfAbsent(level, l -> new LevelTickLoop(l, getMasterPool()));
    }

    /**
     * 该维度 loop 是否已启动接管（{@code tickChildren → scheduleIfNeeded} 之后才为 true）。
     * <p>
     * 启动阶段 {@code prepareLevels} 在 {@code tickChildren} 之前运行，此时 loop 尚未启动，
     * 服务器主线程必须仍走原版 {@code ServerChunkCache.pollTask → runDistanceManagerUpdates}
     * 来驱动出生点区块生成；直到 loop 接管后才由 {@code ServerChunkCachePollMixin} 跳过主线程 poll。
     */
    public boolean isLoopScheduled(ServerLevel level) {
        LevelTickLoop loop = this.loops.get(level);
        return loop != null && loop.isScheduled();
    }

    /**
     * 把玩家的网络连接路由到其所属维度的 loop（幂等）。
     * <p>
     * 用 {@link #connectionRoutes} 记录连接当前所属维度：目标维度与记录一致时是 no-op；不一致
     * （登录首次 / 跨维度传送 / 重生换维度）时先从旧 loop 移除、再加入新 loop。线程安全，可由
     * 主线程（reconcile）与 loop 线程并发调用。
     */
    public void routeConnection(Connection connection, ServerLevel level) {
        ServerLevel old = this.connectionRoutes.put(connection, level);
        if (old != level) {
            if (old != null) {
                LevelTickLoop oldLoop = this.loops.get(old);
                if (oldLoop != null) {
                    oldLoop.removeConnection(connection);
                }
            }
            this.getLoop(level).addConnection(connection);
        }
    }

    /** 玩家下线时彻底移除连接路由（幂等；loop 也会在 {@code tickConnections} 自清理断开连接）。 */
    public void unrouteConnection(Connection connection) {
        ServerLevel old = this.connectionRoutes.remove(connection);
        if (old != null) {
            LevelTickLoop loop = this.loops.get(old);
            if (loop != null) {
                loop.removeConnection(connection);
            }
        }
    }

    /**
     * 主线程每 tick 调用：把每个在线玩家的连接路由到其<b>当前</b>维度 loop。
     * <p>
     * 登录、跨维度传送、重生换维度三大路径的时序复杂，这里用一个幂等的「回收」统一收口：遍历
     * {@code playerList.getPlayers()}，按玩家当前 {@code serverLevel()} 调用 {@link #routeConnection}。
     * 已在正确 loop 中的连接是 no-op，仅发生实际迁移时才增删。单机/少量玩家时开销可忽略。
     */
    public void reconcileConnections(MinecraftServer server) {
        // 快照拷贝：playerList.getPlayers() 返回不可变包装，高负载下（大量假人批量上线/下线）
        // 原列表可能被并发修改 → 遍历报 ConcurrentModificationException。拷贝后迭代安全。
        for (ServerPlayer player : new java.util.ArrayList<>(server.getPlayerList().getPlayers())) {
            if (player.connection != null) {
                routeConnection(player.connection.getConnection(), player.serverLevel());
            }
        }
    }

    /**
     * 单机内嵌服务器暂停/恢复（由客户端 {@code Minecraft.isPaused()} 驱动，见
     * {@code MixinMinecraftPauseDetector}）。
     * <p>
     * 暂停时：先取消所有维度 loop 的下一拍，再等待正在执行的那一拍完全收口（含其内部
     * 并行子任务屏障），使所有维度 worker 彻底停摆——暂停/保存期间不再有任何线程并行写入
     * {@code LevelChunk}；否则维度 worker 与主线程「暂停并保存」并发，区块数据包序列化读到
     * 中间态，客户端解码越界（"网络协议错误"）掉线（历史 bug：ESC 暂停后 0.5s 必掉线）。
     * <p>
     * 恢复时：重新排程所有维度 loop。LAN 开放时客户端 {@code isPaused()} 恒 false，不会误暂停。
     */
    public void setPaused(boolean paused) {
        if (paused) {
            this.loops.values().forEach(LevelTickLoop::pause);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean anyTicking = true;
            while (anyTicking && System.nanoTime() < deadline) {
                anyTicking = false;
                for (LevelTickLoop loop : this.loops.values()) {
                    if (loop.isTicking()) {
                        anyTicking = true;
                        break;
                    }
                }
                if (anyTicking) {
                    LockSupport.parkNanos(100_000L); // 0.1ms 退避
                }
            }
        } else {
            this.loops.values().forEach(LevelTickLoop::resume);
        }
    }

    /** 移除并停止某维度的 loop（维度卸载时调用）。 */
    public void removeLoop(ServerLevel level) {
        LevelTickLoop loop = this.loops.remove(level);
        if (loop != null) {
            loop.kill();
        }
    }

    /**
     * 停止所有维度 loop 并<b>等待正在执行的 tick 完全收口</b>（服务器停机时调用）。
     * <p>
     * 仅 {@link #killAllLoops()}（置 running=false + cancel 下一次自排程）不等待正在运行的 tick：
     * {@code cancel(true)} 只能取消尚未开始的任务，对正在执行 {@code LevelTickLoop.internalTick}
     * 的维度 worker 无效。于是 stopServer 之后主线程的 {@code saveAllChunks} 会与仍在飞的维度
     * worker（{@code level.tick → processUnloads → ChunkMap.toDrop}）并发修改非线程安全的
     * {@code ChunkMap.toDrop}（fastutil Long2ObjectLinkedOpenHashMap），结构损坏后主线程保存
     * 无限卡死（表现为「正在保存中」冻结，只能强杀后台进程）。
     * <p>
     * 因此先 kill 全部 loop（停掉下一拍，配合 ticking 标志阻止再进入），再自旋等待每个 loop 的
     * {@code isTicking()==false}（worker 已完全退出当前 tick，含内部子任务屏障收口），
     * 带 30s 看门狗防止异常场景下永久阻塞。
     */
    public void stopLoopsAndWaitIdle() {
        this.loops.values().forEach(LevelTickLoop::kill);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (LevelTickLoop loop : this.loops.values()) {
            while (loop.isTicking()) {
                if (System.nanoTime() > deadline) {
                    ModZuozhi.LOGGER.warn("[DimThread] 等待维度 {} loop 收口超时（>30s），强制继续",
                            loop.getLevel().dimension().location());
                    break;
                }
                LockSupport.parkNanos(100_000L); // 0.1ms 退避
            }
        }
    }

    /** 停止所有维度 loop 并关闭调度池（服务器停机 / 全局开关关闭时调用）。 */
    public void killAllLoops() {
        this.loops.values().forEach(LevelTickLoop::kill);
        this.loops.clear();
        // 清空连接路由：重新启用时 routeConnection 会把连接当作「首次」重新挂到新 loop。
        // 否则 old == level 会误判为 no-op，导致重启后玩家连接丢失、不再被任何 loop tick。
        this.connectionRoutes.clear();
        ScheduledThreadPoolExecutor pool = this.masterPool;
        if (pool != null) {
            pool.shutdownNow();
            this.masterPool = null;
        }
    }

    public void clear() {
        actives.clear();
        threadPools.clear();
    }
}