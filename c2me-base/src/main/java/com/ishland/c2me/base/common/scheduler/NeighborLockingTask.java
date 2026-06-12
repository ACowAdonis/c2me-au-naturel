package com.ishland.c2me.base.common.scheduler;

import com.google.common.base.Preconditions;
import com.ishland.c2me.base.common.GlobalExecutors;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class NeighborLockingTask<T> implements ScheduledTask {

    private final SchedulingManager schedulingManager;
    private final long[] names;
    private final BooleanSupplier isCancelled;
    private final Supplier<CompletableFuture<T>> action;
    private final CompletableFuture<T> future = new CompletableFuture<>();
    private boolean acquired = false;

    public NeighborLockingTask(SchedulingManager schedulingManager, long[] names, BooleanSupplier isCancelled, Supplier<CompletableFuture<T>> action) {
        this.schedulingManager = schedulingManager;
        this.names = names;
        this.isCancelled = isCancelled;
        this.action = action;

        this.schedulingManager.enqueue(this);
    }

    @Override
    public boolean tryPrepare() {
        if (this.isCancelled.getAsBoolean()) {
            // holder downgraded/unloaded: don't acquire (2r+1)^2 locks and run a full
            // generation nobody needs; consumers map CancellationException to UNLOADED_CHUNK
            this.future.completeExceptionally(new CancellationException());
            return false;
        }
        final NeighborLockingManager lockingManager = this.schedulingManager.getNeighborLockingManager();
        for (long l : names) {
            if (lockingManager.isLocked(l)) {
                lockingManager.addReleaseListener(l, () -> this.schedulingManager.enqueue(this));
                return false;
            }
        }
        for (long l : names) {
            lockingManager.acquireLock(l);
        }
        acquired = true;
        return true;
    }

    @Override
    public void runTask() {
        if (!acquired) throw new IllegalStateException();
        final CompletableFuture<T> future;
        try {
            future = Preconditions.checkNotNull(this.action.get(), "future");
        } catch (Throwable t) {
            // a synchronous throw must not leak the acquired region locks or leave
            // the chunk future incomplete (permanent generation deadlock for the area)
            this.releaseLocks();
            this.future.completeExceptionally(t);
            return;
        }
        future.handleAsync((result, throwable) -> {
            this.releaseLocks();
            if (throwable != null) this.future.completeExceptionally(throwable);
            else this.future.complete(result);
            return null;
        }, GlobalExecutors.invokingExecutor);
    }

    private void releaseLocks() {
        this.schedulingManager.getExecutor().execute(() -> {
            final NeighborLockingManager lockingManager = this.schedulingManager.getNeighborLockingManager();
            for (long l : names) {
                lockingManager.releaseLock(l);
            }
        });
    }

    public CompletableFuture<T> getFuture() {
        return future;
    }
}
