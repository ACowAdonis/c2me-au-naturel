package com.ishland.c2me.fixes.general.threading_issues.mixin;

import net.minecraft.server.world.ChunkLevelType;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.entity.EntityTrackingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards entity tracking status updates against off-thread callers.
 * <p>
 * ServerEntityManager's collections (pendingUnloads, trackingStatuses, ...) are
 * plain fastutil structures owned by the server thread; any unsynchronized
 * structural mutation from another thread corrupts their hash tables and
 * crashes later in tick-time iteration (e.g. AIOOBE in shiftKeys during
 * unloadChunks' removeIf). Rather than throwing, off-thread updates are
 * deferred and applied at the head of the next tick, so the update still
 * happens, the game keeps running, and the offending caller is identified in
 * the log.
 * <p>
 * Ordering: deferred updates are keyed by chunk pos (last write wins), and a
 * main-thread update removes any pending deferred entry for that pos — a stale
 * deferred update can never overwrite a newer main-thread one.
 */
@Mixin(ServerEntityManager.class)
public abstract class MixinServerEntityManager {

    @Unique
    private static final Logger c2me$LOGGER = LoggerFactory.getLogger("C2ME/EntityTrackingGuard");

    @Unique
    private final ConcurrentHashMap<Long, Runnable> c2me$deferredTrackingUpdates = new ConcurrentHashMap<>();

    @Unique
    private volatile Thread c2me$ownerThread = null;

    @Unique
    private volatile long c2me$lastOffThreadWarn = 0L;

    @Inject(method = "tick", at = @At("HEAD"))
    private void c2me$drainDeferredTrackingUpdates(CallbackInfo ci) {
        if (this.c2me$ownerThread == null) this.c2me$ownerThread = Thread.currentThread();
        if (this.c2me$deferredTrackingUpdates.isEmpty()) return;
        final Iterator<Map.Entry<Long, Runnable>> iterator = this.c2me$deferredTrackingUpdates.entrySet().iterator();
        while (iterator.hasNext()) {
            final Runnable update = iterator.next().getValue();
            iterator.remove();
            update.run();
        }
    }

    @Inject(method = "updateTrackingStatus(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/server/world/ChunkLevelType;)V", at = @At("HEAD"), cancellable = true)
    private void c2me$guardTrackingStatusFromLevelType(ChunkPos pos, ChunkLevelType levelType, CallbackInfo ci) {
        if (this.c2me$deferIfOffThread(pos, () -> ((ServerEntityManager<?>) (Object) this).updateTrackingStatus(pos, levelType))) {
            ci.cancel();
        }
    }

    @Inject(method = "updateTrackingStatus(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/world/entity/EntityTrackingStatus;)V", at = @At("HEAD"), cancellable = true)
    private void c2me$guardTrackingStatus(ChunkPos pos, EntityTrackingStatus status, CallbackInfo ci) {
        if (this.c2me$deferIfOffThread(pos, () -> ((ServerEntityManager<?>) (Object) this).updateTrackingStatus(pos, status))) {
            ci.cancel();
        }
    }

    @Unique
    private boolean c2me$deferIfOffThread(ChunkPos pos, Runnable update) {
        final Thread ownerThread = this.c2me$ownerThread;
        if (ownerThread == null || Thread.currentThread() == ownerThread) {
            if (!this.c2me$deferredTrackingUpdates.isEmpty()) this.c2me$deferredTrackingUpdates.remove(pos.toLong());
            return false;
        }
        final long now = System.currentTimeMillis();
        // rate-limit gate runs before any stack capture
        if (now - this.c2me$lastOffThreadWarn > 10_000L) {
            this.c2me$lastOffThreadWarn = now;
            c2me$LOGGER.warn("Off-thread entity tracking status update for {} on thread \"{}\"; deferring to main thread. Caller:",
                    pos, Thread.currentThread().getName(), new Throwable("Off-thread caller"));
        }
        this.c2me$deferredTrackingUpdates.put(pos.toLong(), update);
        return true;
    }

}
