package com.ishland.c2me.base.common.scheduler;

import java.util.concurrent.Executor;

/**
 * Per-world dispatcher for neighbor-locked worldgen tasks.
 * <p>
 * All lock state ({@link NeighborLockingManager}) is confined to the single-threaded
 * {@code c2me-sched} executor passed in here; nothing in this class or in
 * {@link NeighborLockingTask} is safe to drive from any other executor.
 * <p>
 * Tasks run as soon as their neighbor locks are available; a task whose region is
 * locked registers a release listener and is re-enqueued when the conflicting task
 * finishes. There is no admission cap or priority ordering: concurrency is bounded
 * by the worker pool that the task suppliers dispatch onto, and field experience
 * showed the former priority queue never influenced scheduling in practice.
 */
public class SchedulingManager {

    private final NeighborLockingManager neighborLockingManager = new NeighborLockingManager();
    private final Executor executor;

    public SchedulingManager(Executor executor) {
        this.executor = executor;
    }

    public void enqueue(ScheduledTask task) {
        this.executor.execute(() -> {
            if (task.tryPrepare()) {
                task.runTask();
            }
        });
    }

    public NeighborLockingManager getNeighborLockingManager() {
        return this.neighborLockingManager;
    }

    public Executor getExecutor() {
        return executor;
    }

}
