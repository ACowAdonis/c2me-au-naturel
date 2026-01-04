package com.ishland.c2me.fixes.general.threading_issues.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkLevels;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder {

    @Shadow
    @Final
    public static CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> UNLOADED_CHUNK_FUTURE;
    @Shadow
    private int level;

    @Shadow
    protected abstract void combineSavingFuture(CompletableFuture<? extends Either<? extends Chunk, ChunkHolder.Unloaded>> then, String thenDesc);

    @Shadow
    @Final
    private AtomicReferenceArray<CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>>> futuresByStatus;
    @Shadow private CompletableFuture<Chunk> savingFuture;

    /**
     * C2ME fix: Lock ordering documentation to prevent deadlocks.
     *
     * LOCK ORDERING HIERARCHY:
     * 1. schedulingMutex (acquired first, if needed)
     * 2. synchronized(this) (acquired second, if needed)
     *
     * IMPORTANT: These locks must NEVER be held simultaneously. The schedulingMutex
     * is released before acquiring synchronized(this) in getChunkAt().
     *
     * If this ordering is violated, deadlocks may occur under heavy concurrent load
     * (e.g., when running with Chunky, Distant Horizons, or other worldgen mods).
     */
    @Unique
    private final Object schedulingMutex = new Object();

    /**
     * @author ishland
     * @reason improve handling of async chunk request
     */
    @Overwrite
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> getChunkAt(ChunkStatus targetStatus, ThreadedAnvilChunkStorage chunkStorage) {
        // TODO [VanillaCopy]
        int i = targetStatus.getIndex();
        CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> completableFuture = this.futuresByStatus.get(i);
        if (completableFuture != null) {
            Either<Chunk, ChunkHolder.Unloaded> either = completableFuture.getNow(null);
            boolean bl = either != null && either.right().isPresent();
            if (!bl) {
                return completableFuture;
            }
        }

        CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future;

        // C2ME fix: Acquire schedulingMutex first (lock ordering: 1)
        synchronized (this.schedulingMutex) {
            // copied from above
            completableFuture = this.futuresByStatus.get(i);
            if (completableFuture != null) {
                Either<Chunk, ChunkHolder.Unloaded> either = completableFuture.getNow(null);
                boolean bl = either != null && either.right().isPresent();
                if (!bl) {
                    return completableFuture;
                }
            }
            if (ChunkLevels.getStatus(this.level).isAtLeast(targetStatus)) {
                future = new CompletableFuture<>();
                this.futuresByStatus.set(i, future);
                // C2ME - moved down to prevent deadlock
            } else {
                return completableFuture == null ? UNLOADED_CHUNK_FUTURE : completableFuture;
            }
        } // C2ME fix: schedulingMutex is released here before acquiring synchronized(this)

        CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> completableFuture2 = chunkStorage.getChunk((ChunkHolder) (Object) this, targetStatus);
        // C2ME fix: Acquire synchronized(this) second (lock ordering: 2)
        // CRITICAL: schedulingMutex must be released before this point to prevent deadlocks
        synchronized (this) {
            this.combineSavingFuture(completableFuture2, "schedule " + targetStatus);
        }
        completableFuture2.whenComplete((either, throwable) -> {
            if (throwable != null) {
                future.completeExceptionally(throwable);
                return;
            }
            future.complete(either);
        });
        // C2ME fix: Return the placeholder future that was set inside the synchronized block.
        // The placeholder will be completed by the whenComplete handler above.
        // Returning completableFuture2 directly would be fine, but we keep the
        // array consistent with the future reference.
        return future;
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/ChunkHolder;combineSavingFuture(Ljava/util/concurrent/CompletableFuture;Ljava/lang/String;)V"))
    private void synchronizeCombineSavingFuture(ChunkHolder holder, CompletableFuture<? extends Either<? extends Chunk, ChunkHolder.Unloaded>> then, String thenDesc) {
        synchronized (this) {
            this.combineSavingFuture(then.exceptionally(unused -> null), thenDesc);
        }
    }

    /**
     * @author ishland
     * @reason synchronize
     */
    @Overwrite
    public void combineSavingFuture(String string, CompletableFuture<?> completableFuture) {
        synchronized (this) {
            this.savingFuture = this.savingFuture.thenCombine(completableFuture.exceptionally(unused -> null), (chunk, object) -> chunk);
        }
    }

}
