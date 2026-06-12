package com.ishland.c2me.base.common.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;

public class CFUtil {

    public static <T> T join(CompletableFuture<T> future) {
        // parkNanos returns immediately while the interrupt flag is set; without
        // clearing it an interrupted thread busy-spins at 100% CPU until the
        // future completes. Clear it per iteration and restore on exit.
        boolean interrupted = false;
        try {
            while (!future.isDone()) {
                LockSupport.parkNanos("Waiting for future", 100000L);
                if (Thread.interrupted()) interrupted = true;
            }
            return future.join();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

}
