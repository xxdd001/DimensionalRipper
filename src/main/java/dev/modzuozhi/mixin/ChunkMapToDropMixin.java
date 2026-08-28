package dev.modzuozhi.mixin;

import dev.modzuozhi.core.collection.LongSetConcurrentHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 待卸载区块集合（{@code ChunkMap.toDrop}）并发化（借鉴 MCMT/Async 思路，独立实现）。
 * <p>
 * {@code toDrop}（{@code LongOpenHashSet}，记录计划卸载的区块）在维度并行下存在两条
 * <b>不同线程</b>的访问路径：
 * <ul>
 *     <li><b>维度 worker</b>：{@code ServerChunkCache.tick → ChunkMap.processUnloads} 遍历
 *         {@code toDrop.iterator()} 逐个卸载区块；</li>
 *     <li><b>区块卸载调度</b>（玩家移动/ticket 变化等触发，主线程/其它路径）：
 *         {@code prepareEntityTickingChunk} 等并发 {@code add/remove} 同一集合。</li>
 * </ul>
 * fastutil {@code LongOpenHashSet} 遍历与修改并发 → 迭代器损坏（{@code wrapped=null} NPE）→
 * 维度 tick 抛异常（实测：末地维度 {@code processUnloads} 遍历 toDrop 时 NPE）。
 * <p>
 * 修复：用 {@link LongSetConcurrentHashSet}（内部 {@code ConcurrentHashMap}，弱一致迭代）
 * 替换 {@code toDrop} 实例（{@code @Shadow} 初始值替换，与 {@code EntityLookupMixin}/
 * {@code ChunkMapEntityMapMixin} 相同的已验证模式）。遍历与增删并发不再抛 NPE/CME，纯加固。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapToDropMixin {

    @Shadow
    private final LongSet toDrop = new LongSetConcurrentHashSet();
}
