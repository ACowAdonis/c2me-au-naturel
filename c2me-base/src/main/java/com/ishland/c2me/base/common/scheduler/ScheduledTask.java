package com.ishland.c2me.base.common.scheduler;

public interface ScheduledTask {

    /**
     * Attempt to acquire whatever this task needs to run. Runs on the scheduler thread.
     *
     * @return true if acquired and {@link #runTask()} may be invoked; false if the task
     *         arranged its own re-enqueue (lock release listener) or completed itself
     *         (cancellation) — either way the caller is done with it for now
     */
    boolean tryPrepare();

    void runTask();

}
