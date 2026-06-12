package com.ishland.c2me.fixes.general.threading_issues.mixin.asynccatchers;

import net.minecraft.server.world.ChunkLevelType;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.thread.ThreadExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ConcurrentModificationException;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class MixinThreadedAnvilChunkStorage {

    @Unique
    private static final Logger c2me$LOGGER = LoggerFactory.getLogger("C2ME/AsyncCatchers");

    @Shadow @Final private ThreadExecutor<Runnable> mainThreadExecutor;

    @Shadow abstract void onChunkStatusChange(ChunkPos pos, ChunkLevelType levelType);

    @Unique
    private volatile long c2me$lastAsyncStatusWarn = 0L;

    @Inject(method = "loadEntity", at = @At("HEAD"))
    private void preventAsyncEntityLoad(CallbackInfo ci) {
        if (!this.mainThreadExecutor.isOnThread()) {
            throw new ConcurrentModificationException("Async entity load");
        }
    }

    @Inject(method = "unloadEntity", at = @At("HEAD"))
    private void preventAsyncEntityUnload(CallbackInfo ci) {
        if (!this.mainThreadExecutor.isOnThread()) {
            throw new ConcurrentModificationException("Async entity unload");
        }
    }

    // Off-thread full-chunk-status changes reach ServerEntityManager.updateTrackingStatus and
    // structurally modify its collections, racing the main-thread tick iteration. Re-dispatch
    // to the main thread instead of throwing: the update still applies, nothing crashes, and
    // the offender is identified in the log.
    @Inject(method = "onChunkStatusChange(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/server/world/ChunkLevelType;)V", at = @At("HEAD"), cancellable = true)
    private void redispatchAsyncChunkStatusChange(ChunkPos pos, ChunkLevelType levelType, CallbackInfo ci) {
        if (!this.mainThreadExecutor.isOnThread()) {
            final long now = System.currentTimeMillis();
            // rate-limit gate runs before any stack capture
            if (now - this.c2me$lastAsyncStatusWarn > 10_000L) {
                this.c2me$lastAsyncStatusWarn = now;
                c2me$LOGGER.warn("Async chunk full-status change for {} -> {} on thread \"{}\"; re-dispatching to main thread. Caller:",
                        pos, levelType, Thread.currentThread().getName(), new Throwable("Off-thread caller"));
            }
            this.mainThreadExecutor.execute(() -> this.onChunkStatusChange(pos, levelType));
            ci.cancel();
        }
    }

}
