package dev.modzuozhi.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 玩家列表（{@code ServerLevel.players}）并发化（借鉴 MCMT/Async 思路，独立实现）。
 * <p>
 * {@code players}（共享 {@code ArrayList<ServerPlayer>}）在维度并行下存在两条<b>不同线程</b>
 * 的访问路径：
 * <ul>
 *     <li><b>维度 worker</b>（实体 tick）：生物 AI 的 {@code getNearestPlayer → getNearestEntity}
 *         <b>遍历</b>该列表（如苦力怕 {@code NearestAttackableTargetGoal.findTarget}）；</li>
 *     <li><b>主线程</b>：玩家加入/退出/跨维度传送/成就进度触发 {@code add/remove} 同一列表。</li>
 * </ul>
 * {@code ArrayList} 遍历与修改并发 → {@code ConcurrentModificationException} → 维度 tick 抛异常
 * （实测：主世界生物 AI 遍历 players 时 CME，偶发且被维度循环捕获）。
 * <p>
 * 修复：用 {@link CopyOnWriteArrayList}（读多写少，迭代期间并发增删不抛 CME）替换 {@code players}
 * 实例（{@code @Shadow} 初始值替换，与 {@code EntityLookupMixin}/{@code ChunkMapEntityMapMixin}
 * 相同的已验证模式）。纯加固，不改变任何 tick 归属。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPlayersMixin {

    @Shadow
    private final List<ServerPlayer> players = new CopyOnWriteArrayList<>();
}
