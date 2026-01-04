package com.ishland.c2me.threading.chunkio.common;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.concurrent.LinkedBlockingQueue;

public class ChunkIoMainThreadTaskUtils {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<ArrayDeque<ReferenceArrayList<Runnable>>> deserializeStack = ThreadLocal.withInitial(ArrayDeque::new);
    private static final LinkedBlockingQueue<Runnable> mainThreadQueue = new LinkedBlockingQueue<>();

    // C2ME fix: Maximum allowed stack depth to prevent memory leaks
    private static final int MAX_STACK_DEPTH = 16;
    // C2ME fix: Threshold for warning about potential leaks
    private static final int WARN_STACK_DEPTH = 8;

    public static void push(ReferenceArrayList<Runnable> queue) {
        if (queue == null) {
            throw new IllegalArgumentException("Queue cannot be null");
        }
        final ArrayDeque<ReferenceArrayList<Runnable>> stack = deserializeStack.get();
        final int currentDepth = stack.size();

        // C2ME fix: Detect and prevent unbounded stack growth
        if (currentDepth >= MAX_STACK_DEPTH) {
            LOGGER.error("Deserialization stack depth exceeded maximum ({}). This indicates a task queue leak! Clearing stack to prevent memory leak.", MAX_STACK_DEPTH, new Throwable());
            stack.clear();
        } else if (currentDepth >= WARN_STACK_DEPTH) {
            LOGGER.warn("Deserialization stack depth ({}) is unusually high. Possible task queue leak from incompatible mod?", currentDepth, new Throwable());
        }

        stack.push(queue);
    }

    public static void pop(ReferenceArrayList<Runnable> queue) {
        if (queue == null) {
            throw new IllegalArgumentException("Queue cannot be null");
        }
        final ArrayDeque<ReferenceArrayList<Runnable>> stack = deserializeStack.get();
        if (stack.isEmpty()) {
            LOGGER.error("Attempted to pop task queue but stack is empty. Task queue leak or double-pop detected!", new Throwable());
            return;
        }
        if (stack.peek() != queue) {
            LOGGER.error("Task queue mismatch during pop! Clearing entire stack to recover.", new Throwable());
            stack.clear();
            throw new IllegalStateException("Unexpected queue");
        }
        stack.pop();

        // C2ME fix: Defensive cleanup - if stack is empty, ensure ThreadLocal is cleaned
        if (stack.isEmpty()) {
            deserializeStack.remove();
        }
    }

    /**
     * C2ME fix: Forcefully clear the deserialization stack for the current thread.
     * This should be called as a last resort to recover from task queue leaks.
     */
    public static void clearStack() {
        final ArrayDeque<ReferenceArrayList<Runnable>> stack = deserializeStack.get();
        if (!stack.isEmpty()) {
            LOGGER.warn("Forcefully clearing deserialization stack with {} entries. This may indicate a task queue leak.", stack.size(), new Throwable());
            stack.clear();
            deserializeStack.remove();
        }
    }

    /**
     * C2ME fix: Get current stack depth for monitoring purposes.
     */
    public static int getStackDepth() {
        return deserializeStack.get().size();
    }

    public static void executeMain(Runnable command) {
        final ArrayDeque<ReferenceArrayList<Runnable>> stack = deserializeStack.get();
        if (stack.isEmpty()) command.run();
        else stack.peek().add(command);
    }

    public static void drainQueue(ReferenceArrayList<Runnable> queue) {
        for (Runnable command : queue) {
            try {
                command.run();
            } catch (Throwable t) {
                LOGGER.error("Error while executing main thread task", t);
            }
        }
        queue.clear();
    }

}
