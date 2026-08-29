package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 让 {@code Level.getChunk(int, int)} 对「区块未就绪」场景健壮。
 * <p>
 * 原版 {@code Level.getChunk(int, int)} 直接 {@code (LevelChunk) this.getChunk(x, z, FULL)}。
 * 服务端主线程下，若目标区块尚未 FULL，会先同步完成生成再返回真实 {@code LevelChunk}，强转不会失败。
 * <p>
 * 但细粒度并行 / 维度并行下，子任务线程（伪装成主线程走 {@code ServerChunkCache} 主线程路径）请求
 * 一个<b>正被主线程生成</b>的相邻区块时，返回的是 {@code ImposterProtoChunk}（世界生成占位块，
 * {@code extends ProtoChunk}，<b>并非</b> {@code LevelChunk} 子类），强转失败抛
 * {@code ClassCastException}（例如草/泥土蔓延 {@code SpreadingSnowyDirtBlock.randomTick} 通过
 * {@code getBlockState} 访问相邻未就绪区块时）。
 * <p>
 * 这里在强转前，若结果是 {@code ImposterProtoChunk}，则取其 {@code getWrapped()}（其内部委托的真实
 * {@code LevelChunk}，其 {@code getBlockState}/{@code getSections} 均转发到 wrapped），既避免崩溃，
 * 语义也与原版一致。单线程场景该分支从不触发，是空操作。
 * <p>
 * 用 {@link WrapOperation} 而非 {@code @Redirect}，以便与其它模组对同一内部调用的
 * {@code @Redirect}（如 Lithium 的 {@code world.chunk_access.LevelMixin}）共存：
 * {@code @WrapOperation} 可与它们链式共存，不会因「merged by ... 无法注入」或互相跳过而崩。
 * 保持默认优先级即可。性能等价：正常路径经 {@code original.call} 走原逻辑，仅多一次纳秒级间接调用。
 */
@Mixin(Level.class)
public abstract class LevelGetChunkMixin {
    @WrapOperation(method = "getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;)Lnet/minecraft/world/level/chunk/ChunkAccess;"))
    private ChunkAccess modzuozhi_getRealChunk(Level level, int x, int z, ChunkStatus status, Operation<ChunkAccess> original) {
        ChunkAccess chunk = original.call(level, x, z, status);
        if (chunk instanceof ImposterProtoChunk imposter) {
            return imposter.getWrapped();
        }
        return chunk;
    }
}
