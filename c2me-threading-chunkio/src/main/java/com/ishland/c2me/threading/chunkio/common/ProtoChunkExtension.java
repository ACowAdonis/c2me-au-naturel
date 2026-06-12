package com.ishland.c2me.threading.chunkio.common;


import java.util.concurrent.CompletableFuture;

public interface ProtoChunkExtension {

    void setInitialMainThreadComputeFuture(CompletableFuture<Void> future);
    CompletableFuture<Void> getInitialMainThreadComputeFuture();

    /**
     * Tail of the deferred onBlockChanged notification chain. Deferred
     * notifications must run in submission order (POI add/remove for the same
     * position is order-sensitive); chaining through this tail guarantees it,
     * whereas independent thenRun registrations on one future run LIFO.
     * Guarded by synchronizing on this chunk instance.
     */
    CompletableFuture<Void> c2me$getNotifyChainTail();
    void c2me$setNotifyChainTail(CompletableFuture<Void> tail);

}
