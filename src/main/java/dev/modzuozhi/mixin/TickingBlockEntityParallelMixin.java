package dev.modzuozhi.mixin;

import dev.modzuozhi.core.dimthread.DimThreadCore;
import dev.modzuozhi.core.dimthread.FineGrainScheduler;
import dev.modzuozhi.core.dimthread.PostExecuteQueue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.modzuozhi.core.filter.SerDesFilter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * 方块实体（TE）细粒度并行（借鉴 MCMT 思路，写我们自己的实现）。
 * <p>
 * 在 {@code Level.tickBlockEntities()} 的 while 循环中，把线程安全（经
 * {@link SerDesFilter} 判定可并行）的 TE tick 提交到 {@link FineGrainScheduler.Barrier}
 * 并行执行，其余（黑名单/非 vanilla）串行；循环结束后 {@code await} 收口，再
 * {@code drain} 子任务投递的延迟回调。
 * <p>
 * 线程安全关键：并行子任务运行在其它 worker 线程，可能并发写 {@code pendingBlockEntityTickers}
 * 等普通集合。这里拦截 {@code addBlockEntityTicker} / {@code addFreshBlockEntities}，
 * 在并行子任务上下文中把写入投递到 {@link PostExecuteQueue}，由维度 worker 串行执行。
 * <p>
 * 仅在 {@code /dimensionalripper fine on} 且维度并行激活、当前线程为维度 worker 时生效；
 * 关闭时完全退化为原版串行。
 */
@Mixin(Level.class)
public abstract class TickingBlockEntityParallelMixin {
    @Shadow
    private void addBlockEntityTicker(TickingBlockEntity ticker) {
    }

    @Shadow
    private void addFreshBlockEntities(Collection<BlockEntity> entities) {
    }

    @Unique
    private FineGrainScheduler.Barrier modzuozhi_teBarrier;
    @Unique
    private boolean modzuozhi_teParallel;

    @Unique
    private boolean modzuozhi_shouldParallel() {
        if (!FineGrainScheduler.isEnabled()) {
            return false;
        }
        if (!((Object) this instanceof ServerLevel serverLevel)) {
            return false;
        }
        MinecraftServer server = serverLevel.getServer();
        return server != null
                && DimThreadCore.MANAGER.isActive(server)
                && DimThreadCore.owns(Thread.currentThread());
    }

    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void modzuozhi_teBegin(CallbackInfo ci) {
        this.modzuozhi_teParallel = modzuozhi_shouldParallel();
        if (this.modzuozhi_teParallel) {
            this.modzuozhi_teBarrier = FineGrainScheduler.beginBarrier();
        }
    }

    /**
     * 用 {@link WrapOperation} 而非 {@code @Redirect} 包裹 {@code TickingBlockEntity.tick()} 调用，
     * 以便与其它模组（如 Observable）对该调用点的 {@code @Redirect} 共存，避免「@Redirect conflict → 关键注入失败」启动崩溃。
     * 性能等价：并行分支不调用 {@code original}，行为与原先一致。
     */
    @WrapOperation(method = "tickBlockEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void modzuozhi_teTick(TickingBlockEntity ticker, Operation<Void> original) {
        if (this.modzuozhi_teParallel && SerDesFilter.INSTANCE.isParallel(ticker)) {
            MinecraftServer server = ((ServerLevel) (Object) this).getServer();
            this.modzuozhi_teBarrier.submit(server, ticker::tick, ticker.getPos());
        } else {
            original.call(ticker);
        }
    }

    @Inject(method = "tickBlockEntities", at = @At("RETURN"))
    private void modzuozhi_teEnd(CallbackInfo ci) {
        if (this.modzuozhi_teParallel && this.modzuozhi_teBarrier != null) {
            this.modzuozhi_teBarrier.await();
            this.modzuozhi_teBarrier.drain();
            this.modzuozhi_teBarrier = null;
            this.modzuozhi_teParallel = false;
        }
    }

    @Inject(method = "addBlockEntityTicker", at = @At("HEAD"), cancellable = true)
    private void modzuozhi_deferTicker(TickingBlockEntity ticker, CallbackInfo ci) {
        PostExecuteQueue queue = FineGrainScheduler.currentQueue();
        if (queue != null) {
            queue.post(() -> this.addBlockEntityTicker(ticker));
            ci.cancel();
        }
    }

    @Inject(method = "addFreshBlockEntities", at = @At("HEAD"), cancellable = true)
    private void modzuozhi_deferFresh(Collection<BlockEntity> entities, CallbackInfo ci) {
        PostExecuteQueue queue = FineGrainScheduler.currentQueue();
        if (queue != null) {
            queue.post(() -> this.addFreshBlockEntities(entities));
            ci.cancel();
        }
    }
}
