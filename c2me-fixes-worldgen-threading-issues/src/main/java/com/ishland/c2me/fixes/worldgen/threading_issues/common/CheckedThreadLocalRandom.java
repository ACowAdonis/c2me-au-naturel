package com.ishland.c2me.fixes.worldgen.threading_issues.common;

import com.ishland.c2me.fixes.worldgen.threading_issues.common.debug.SMAPSourceDebugExtension;
import net.minecraft.util.math.random.LocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class CheckedThreadLocalRandom extends LocalRandom {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheckedThreadLocalRandom");

    private static final ThreadLocal<LocalRandom> FALLBACK = ThreadLocal.withInitial(() -> new LocalRandom(new Random().nextLong()));

    // Rate limiting: track unique caller locations to avoid log spam
    // Key is the caller class+method+line from stack trace
    private static final Set<String> LOGGED_LOCATIONS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Gate before the location dedup: identifying the caller requires a full stack
    // walk (tens of microseconds), and off-thread access can be per-block hot when a
    // mod rolls RNG inside threaded worldgen. Cap diagnostic cost at one stack walk
    // per second; distinct offenders still get logged within a few seconds.
    private static final AtomicLong LAST_STACK_WALK_TIME = new AtomicLong();

    static {
        if (Config.enforceSafeWorldRandomAccess) {
            LOGGER.info("Enforcing safe world random access");
        } else {
            LOGGER.warn("Not enforcing safe world random access");
        }
    }

    private final Supplier<Thread> owner;

    public CheckedThreadLocalRandom(long seed, Supplier<Thread> owner) {
        super(seed);
        this.owner = Objects.requireNonNull(owner);
    }

    private boolean isSafe() {
        Thread owner = this.owner != null ? this.owner.get() : null;
        boolean notOwner = owner != null && Thread.currentThread() != owner;
        if (notOwner) {
            handleNotOwner();
            return false;
        } else {
            return true;
        }
    }

    private void handleNotOwner() {
        // When not enforcing, gate BEFORE the expensive stack walk, then dedup by
        // caller location so each offender is logged once
        if (!Config.enforceSafeWorldRandomAccess) {
            final long now = System.currentTimeMillis();
            final long last = LAST_STACK_WALK_TIME.get();
            if (now - last < 1000L || !LAST_STACK_WALK_TIME.compareAndSet(last, now)) {
                return;
            }
            if (!LOGGED_LOCATIONS.add(getCallerLocation())) {
                return; // Already logged this location
            }
        }

        StringBuilder builder = new StringBuilder();
        final String exceptionMessage = "ThreadLocalRandom accessed from a different thread (owner: %s, current: %s)"
                .formatted(this.owner.get().getName(), Thread.currentThread().getName());
        builder.append(exceptionMessage).append('\n');
        builder.append("This is usually NOT a bug in C2ME, but a bug in another mod or in vanilla code. \n");
        builder.append("Possible solutions: \n");
        builder.append("  - Find possible causes in the stack trace below and \n");
        builder.append("    - if caused by another mod, report this to the corresponding mod authors \n");
        builder.append("    - if no other mods are involved, report this to C2ME \n");
        ConcurrentModificationException exception = new ConcurrentModificationException(exceptionMessage);
        try {
            SMAPSourceDebugExtension.enhanceStackTrace(exception, false);
        } catch (Throwable t) {
            LOGGER.error("Error occurred while processing error stack trace", t);
            exception = new ConcurrentModificationException(exceptionMessage);
        }

        final String s = builder.toString();
        if (Config.enforceSafeWorldRandomAccess) {
            LOGGER.error(s, exception);
            throw new RuntimeException(String.format("%s \n (You may make this a fatal warning instead of a hard crash with fixes.enforceSafeWorldRandomAccess setting in c2me.toml)", s), exception) {
                @Override
                public synchronized Throwable fillInStackTrace() {
                    return this;
                }
            };
        } else {
            // Log at WARN level (not ERROR) when not enforcing, with note about rate limiting
            LOGGER.warn("{}\n(This warning is shown once per unique caller location)", s, exception);
        }
    }

    /**
     * Extract a unique identifier for the caller location from the stack trace.
     * Skips internal CheckedThreadLocalRandom and BitRandomSource frames.
     */
    private static String getCallerLocation() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            // Skip internal frames. Match by simple-name fragments that survive
            // both Yarn (dev) and SRG/Mojmap (production) remapping; an exact
            // check for java.lang.Thread - contains("Thread") would also skip
            // real callers like ThreadedAnvilChunkStorage and misattribute them
            if (className.contains("CheckedThreadLocalRandom") ||
                className.contains("BitRandomSource") ||
                className.equals("java.lang.Thread")) {
                continue;
            }
            // Return first external frame as the unique location
            return className + "." + element.getMethodName() + ":" + element.getLineNumber();
        }
        return "unknown";
    }

    @Override
    public void setSeed(long seed) {
        if (isSafe()) {
            super.setSeed(seed);
        } else {
            FALLBACK.get().setSeed(seed);
        }
    }

    @Override
    public int next(int bits) {
        if (isSafe()) {
            return super.next(bits);
        } else {
            return FALLBACK.get().next(bits);
        }
    }
}
