package dev.modzuozhi.mixin;

import dev.modzuozhi.core.collection.ConcurrentLong2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 区块持有者核心表（{@code ChunkMap.updatingChunkMap}/{@code visibleChunkMap}）并发化
 * （借鉴 MCMT/Async 思路，独立实现）。
 * <p>
 * {@code updatingChunkMap}（待更新区块表）与 {@code visibleChunkMap}（可见区块表，由前者
 * {@code clone()} 生成）是 fastutil {@code Long2ObjectLinkedOpenHashMap}，维度并行下存在
 * <b>不同线程</b>的并发访问：
 * <ul>
 *     <li><b>维度 worker</b>：{@code processUnloads/getChunks/acquireGeneration} 遍历/查询/
 *         删除区块；</li>
 *     <li><b>区块加载/卸载调度</b>：{@code scheduleUnload} 的 {@code CompletableFuture} 异步回调、
 *         {@code prepareEntityTickingChunk} 等并发 {@code put/remove}。</li>
 * </ul>
 * fastutil 链表哈希表遍历与修改并发 → 内部数组损坏（实测：服务器保存时
 * {@code Long2ObjectLinkedOpenHashMap.rehash} Index=-1 越界，区块保存失败）。
 * <p>
 * 修复：用 {@link ConcurrentLong2ObjectLinkedOpenHashMap}（内部 {@code ConcurrentHashMap}，
 * 弱一致迭代，{@code clone} 正确复制内容）替换两个字段实例（{@code @Shadow} 初始值替换，
 * 与 {@code EntityLookupMixin}/{@code ChunkMapEntityMapMixin} 相同的已验证模式）。纯加固，
 * 不改变任何调度逻辑。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapBlockMapMixin {

    @Shadow
    private final Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap =
            new ConcurrentLong2ObjectLinkedOpenHashMap<>();

    @Shadow
    private final Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap =
            new ConcurrentLong2ObjectLinkedOpenHashMap<>();
}
