package com.ishland.c2me.threading.chunkio.mixin;

import com.ishland.c2me.threading.chunkio.common.ProtoChunkExtension;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkRegion.class)
public abstract class MixinChunkRegion implements StructureWorldAccess {

    @WrapOperation(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/ServerWorld;onBlockChanged(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/BlockState;)V"))
    private void waitForFutureBeforeNotifyChanges(ServerWorld instance, BlockPos pos, BlockState oldBlock, BlockState newBlock, Operation<Void> operation) {
        final Chunk chunk = this.getChunk(pos);
        if (chunk instanceof ProtoChunk protoChunk) {
            final ProtoChunkExtension ext = (ProtoChunkExtension) protoChunk;
            // Deferred notifications must apply in submission order: POI add/remove
            // for the same position is order-sensitive, and independent thenRun
            // registrations on one future execute LIFO. Chain through a per-chunk
            // tail instead. The tail check also prevents an inline call from
            // overtaking still-pending chained notifications after the base future
            // completes.
            synchronized (ext) {
                final CompletableFuture<Void> tail = ext.c2me$getNotifyChainTail();
                CompletableFuture<Void> base = null;
                if (tail != null && !tail.isDone()) {
                    base = tail;
                } else {
                    final CompletableFuture<Void> future = ext.getInitialMainThreadComputeFuture();
                    if (future != null && !future.isDone()) {
                        base = future;
                    }
                }
                if (base != null) {
                    ext.c2me$setNotifyChainTail(base.thenRun(() -> operation.call(instance, pos, oldBlock, newBlock)));
                    return;
                }
            }
        }
        operation.call(instance, pos, oldBlock, newBlock);
    }

}
