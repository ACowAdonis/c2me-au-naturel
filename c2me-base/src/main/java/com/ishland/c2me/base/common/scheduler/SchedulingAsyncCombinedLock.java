package com.ishland.c2me.base.common.scheduler;

import com.google.common.base.Preconditions;
import com.ibm.asyncutil.locks.AsyncLock;
import com.ibm.asyncutil.locks.AsyncNamedLock;
import com.ishland.c2me.base.common.GlobalExecutors;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class SchedulingAsyncCombinedLock<T> implements ScheduledTask {

    private static final Logger LOGGER = LoggerFactory.getLogger("C2ME/SchedulingAsyncCombinedLock");

    private enum State {
        PENDING,    // Initial state, ready to acquire
        ACQUIRING,  // Currently attempting to acquire locks
        COMPLETED   // Acquisition completed (success or failure)
    }

    private final AsyncNamedLock<ChunkPos> lock;
    private final long center;
    private final ChunkPos[] names;
    private final BooleanSupplier isCancelled;
    private final Consumer<SchedulingAsyncCombinedLock<T>> readdForExecution;
    private final Supplier<CompletableFuture<T>> action;
    private final String desc;
    private final CompletableFuture<T> future = new CompletableFuture<>();
    private final boolean async;
    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    private AsyncLock.LockToken acquiredToken;

    public SchedulingAsyncCombinedLock(AsyncNamedLock<ChunkPos> lock, long center, Set<ChunkPos> names, BooleanSupplier isCancelled, Consumer<SchedulingAsyncCombinedLock<T>> readdForExecution, Supplier<CompletableFuture<T>> action, String desc, boolean async) {
        this.lock = lock;
        this.center = center;
        this.names = names.toArray(ChunkPos[]::new);
        this.isCancelled = isCancelled;
        this.readdForExecution = readdForExecution;
        this.action = action;
        this.desc = desc;
        this.async = async;

        this.readdForExecution.accept(this);
    }

    @Override
    public boolean tryPrepare() {
        return tryAcquire();
    }

    boolean tryAcquire() {
        // Lock-free state machine: use CAS to ensure only one thread enters acquisition logic
        if (!state.compareAndSet(State.PENDING, State.ACQUIRING)) {
            return false; // Already being acquired by another thread
        }

//        if (this.isCancelled.getAsBoolean()) {
////            System.out.println(String.format("Cancelling tasks for %s", this.desc));
//            this.future.completeExceptionally(new CancellationException());
//            return false;
//        }

        final LockEntry[] tryLocks = new LockEntry[names.length];
        boolean allAcquired = true;
        for (int i = 0, namesLength = names.length; i < namesLength; i++) {
            ChunkPos name = names[i];
            final LockEntry entry = new LockEntry(name, this.lock.tryLock(name));
            tryLocks[i] = entry;
            if (entry.lockToken.isEmpty()) {
                allAcquired = false;
                break;
            }
        }
        if (allAcquired) {
            state.set(State.COMPLETED);
            this.acquiredToken = () -> {
                for (LockEntry entry : tryLocks) {
                    //noinspection OptionalGetWithoutIsPresent
                    entry.lockToken.get().releaseLock(); // if it isn't present then something is really wrong
                }
            };
            return true;
        } else {
            // Reset to PENDING to allow retry
            state.set(State.PENDING);

            boolean triedRelock = false;
            for (LockEntry entry : tryLocks) {
                if (entry == null) continue;
                entry.lockToken.ifPresent(AsyncLock.LockToken::releaseLock);
                if (!triedRelock && entry.lockToken.isEmpty()) {
                    // C2ME fix: Add timeout monitoring for lock acquisition
                    final long lockAcquireStartTime = System.currentTimeMillis();
                    final ChunkPos lockName = entry.name;
                    this.lock.acquireLock(entry.name).thenAccept(lockToken -> {
                        final long lockAcquireTime = System.currentTimeMillis() - lockAcquireStartTime;
                        if (lockAcquireTime > 5000) {
                            LOGGER.warn("Lock acquisition for chunk {} took {} ms for task '{}'. This may indicate lock contention or a deadlock.", lockName, lockAcquireTime, this.desc);
                        }
                        lockToken.releaseLock();
                        this.readdForExecution.accept(this);
                    });
                    triedRelock = true;
                }
            }
            if (!triedRelock) {
                // shouldn't happen at all...
                LOGGER.warn("Some issue occurred while doing locking, retrying");
                return this.tryAcquire();
            }
            return false;
        }
    }

    @Override
    public void runTask(Runnable postAction) {
        Preconditions.checkNotNull(postAction);
        AsyncLock.LockToken token = this.acquiredToken;
        if (token == null) throw new IllegalStateException();
        final CompletableFuture<T> future = this.action.get();
        Preconditions.checkNotNull(future, "future");
        future.handleAsync((result, throwable) -> {
            try {
                token.releaseLock();
            } catch (Throwable t) {
                LOGGER.error("Error releasing lock token", t);
            }
            try {
                postAction.run();
            } catch (Throwable t) {
                LOGGER.error("Error running post action", t);
            }
            if (throwable != null) this.future.completeExceptionally(throwable);
            else this.future.complete(result);
            return null;
        }, GlobalExecutors.invokingExecutor);
    }

    @Override
    public long centerPos() {
        return center;
    }

    @Override
    public boolean isAsync() {
        return this.async;
    }

    public CompletableFuture<T> getFuture() {
        return this.future;
    }

    private record LockEntry(ChunkPos name,
                             Optional<AsyncLock.LockToken> lockToken) {
    }
}
