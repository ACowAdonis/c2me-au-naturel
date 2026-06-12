package com.ishland.c2me.threading.chunkio.mixin;

import com.ishland.c2me.threading.chunkio.common.ProtoChunkExtension;
import net.minecraft.world.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.CompletableFuture;

@Mixin(ProtoChunk.class)
public class MixinProtoChunk implements ProtoChunkExtension {

    @Unique
    private CompletableFuture<Void> initialMainThreadComputeFuture = CompletableFuture.completedFuture(null);

    @Override
    public void setInitialMainThreadComputeFuture(CompletableFuture<Void> future) {
        this.initialMainThreadComputeFuture = future;
    }

    @Override
    public CompletableFuture<Void> getInitialMainThreadComputeFuture() {
        return this.initialMainThreadComputeFuture;
    }

    private CompletableFuture<Void> c2me$notifyChainTail = null;

    @Override
    public CompletableFuture<Void> c2me$getNotifyChainTail() {
        return this.c2me$notifyChainTail;
    }

    @Override
    public void c2me$setNotifyChainTail(CompletableFuture<Void> tail) {
        this.c2me$notifyChainTail = tail;
    }

}
