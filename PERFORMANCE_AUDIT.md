# C2ME Performance and Correctness Audit

## Executive Summary

This document contains a comprehensive audit of the C2ME codebase identifying 22 distinct issues across algorithmic improvements, performance opportunities, correctness bugs, and code quality concerns. Issues are organized by priority tier for systematic remediation.

---

## TIER 1: High Impact, Easy/Medium Difficulty

These issues provide immediate measurable benefits with reasonable implementation effort.

### Issue 2.3: CMETrackingMap Excessive Throwable Allocation

**File**: `c2me-base/src/main/java/com/ishland/c2me/base/common/util/CMETrackingMap.java`
**Lines**: 14, 48, 54, 60, 66
**Severity**: HIGH
**Difficulty**: EASY
**Impact**: Performance - Critical hotpath overhead

**Description**:
Every map mutation operation (`put()`, `remove()`, `putAll()`, `clear()`) instantiates a new `Throwable` object to capture the stack trace. `Throwable` construction includes full stack trace generation which is extremely expensive. On mutation-heavy workloads, this creates massive overhead.

Current code pattern:
```java
@Override
public V put(K key, V value) {
    this.lastOp = new Throwable();  // EXPENSIVE!
    return super.put(key, value);
}
```

**Suggested Fix**:
Replace full `Throwable` instantiation with lightweight tracking. Only capture stack traces when actual `ConcurrentModificationException` is detected:

```java
private volatile long lastOpTimestamp = 0;
private volatile long lastOpSequence = 0;
private static final AtomicLong sequenceGenerator = new AtomicLong();
private volatile Throwable lastOpStackTrace = null;  // Only captured on demand

@Override
public V put(K key, V value) {
    this.lastOpTimestamp = System.nanoTime();
    this.lastOpSequence = sequenceGenerator.incrementAndGet();
    return super.put(key, value);
}

// Only capture stack trace when CME is actually thrown
private void captureStackTraceOnError() {
    this.lastOpStackTrace = new Throwable();
}
```

**Expected Benefit**: Eliminates 5 Throwable allocations per map mutation cycle. On high-throughput paths, this could save milliseconds per operation.

---

### Issue 2.5: Unnecessary Function.identity() Wrappers

**Files**:
- `c2me-base/src/main/java/com/ishland/c2me/base/common/util/AsyncCombinedLock.java:70`
- `c2me-base/src/main/java/com/ishland/c2me/base/common/scheduler/SchedulingAsyncCombinedLock.java:138`
- `c2me-rewrites-chunkio/src/main/java/com/ishland/c2me/rewrites/chunkio/common/C2MEStorageThread.java:159, 184, 248`

**Severity**: MEDIUM
**Difficulty**: EASY
**Impact**: Performance - Unnecessary future stage allocation

**Description**:
Multiple methods use `.thenApply(Function.identity())` to wrap CompletableFuture results. This creates an unnecessary intermediate CompletableFuture stage without functional purpose, adding object allocation and completion chain latency.

Current pattern:
```java
public CompletableFuture<T> getFuture() {
    return this.future.thenApply(Function.identity());  // Unnecessary wrapper
}
```

**Suggested Fix**:
Remove the `.thenApply(Function.identity())` entirely and return the future directly:

```java
public CompletableFuture<T> getFuture() {
    return this.future;
}
```

**Expected Benefit**: Eliminates unnecessary object allocation on every future retrieval. Reduces completion chain depth by 1 stage.

---

### Issue 2.4: Dead Timing Code in SchedulingManager

**File**: `c2me-base/src/main/java/com/ishland/c2me/base/common/scheduler/SchedulingManager.java`
**Lines**: 107-114
**Severity**: MEDIUM
**Difficulty**: EASY
**Impact**: Performance - Unnecessary system calls

**Description**:
The `updateSyncLoadInternal()` method captures `startTime` and `endTime` using `System.nanoTime()` but never uses these values. This adds overhead from system calls on every sync load update (frequent operation).

```java
public void updateSyncLoadInternal(long pos) {
    final long startTime = System.nanoTime();  // NEVER USED
    // ... actual work ...
    final long endTime = System.nanoTime();    // NEVER USED
}
```

**Suggested Fix**:
Remove the unused timing variables:

```java
public void updateSyncLoadInternal(long pos) {
    // ... actual work without timing ...
}
```

**Expected Benefit**: Eliminates 2 system calls per sync load update. Minor but measurable reduction in CPU overhead.

---

### Issue 3.5: ThreadLocal Memory Leak in AsyncSerializationManager

**File**: `c2me-threading-chunkio/src/main/java/com/ishland/c2me/threading/chunkio/common/AsyncSerializationManager.java`
**Lines**: 39-83
**Severity**: HIGH
**Difficulty**: MEDIUM
**Impact**: Correctness - Memory leak in thread pools

**Description**:
The scope stack uses `ThreadLocal<ArrayDeque<Scope>>` which is only cleaned up when the stack becomes empty (lines 80-82). If an exception occurs between `push()` and `pop()`, or if `pop()` is never called, the ThreadLocal retains references indefinitely. In thread pool environments (which C2ME uses), this causes memory leaks as threads are reused.

Current pattern:
```java
static void push(Scope scope) {
    ArrayDeque<Scope> stack = SCOPE_STACK.get();
    if (stack == null) {
        stack = new ArrayDeque<>();
        SCOPE_STACK.set(stack);
    }
    stack.push(scope);
}

static void pop() {
    ArrayDeque<Scope> stack = SCOPE_STACK.get();
    stack.pop();
    if (stack.isEmpty()) {
        SCOPE_STACK.remove();  // Only cleans up if empty!
    }
}
```

**Suggested Fix**:
1. Wrap all scope operations in try-finally blocks at call sites
2. Add helper method to guarantee cleanup:

```java
public static <T> T withScope(Scope scope, Supplier<T> operation) {
    push(scope);
    try {
        return operation.get();
    } finally {
        pop();
    }
}
```

3. Add cleanup on pop even if not empty, with max depth protection

**Expected Benefit**: Prevents memory leaks in long-running servers. Critical for production stability.

---

### Issue 4.2: Unused Variables in SchedulingManager

**File**: `c2me-base/src/main/java/com/ishland/c2me/base/common/scheduler/SchedulingManager.java`
**Lines**: 107-114
**Severity**: LOW
**Difficulty**: EASY
**Impact**: Code Quality - Dead code

**Description**:
Same as Issue 2.4 (can be fixed together). Variables `startTime` and `endTime` are declared but never used.

**Suggested Fix**: Remove the variables.

**Expected Benefit**: Code clarity.

---

### Issue 4.3: Missing @Nullable Annotations

**File**: `c2me-base/src/main/java/com/ishland/c2me/base/common/structs/DynamicPriorityQueue.java`
**Lines**: 55-67
**Severity**: LOW
**Difficulty**: EASY
**Impact**: Code Quality - API documentation

**Description**:
The `dequeue()` method can return `null` when all priority buckets are empty, but lacks `@Nullable` annotation warning callers.

**Suggested Fix**:
Add `@Nullable` annotation:

```java
@Nullable
public T dequeue() {
    // ... existing implementation ...
}
```

**Expected Benefit**: Better IDE warnings and API documentation.

---

## TIER 2: High Impact, Hard

These issues provide significant benefits but require careful implementation.

### Issue 2.1: Method-Level Synchronization Bottleneck

**Files**:
- `c2me-base/src/main/java/com/ishland/c2me/base/common/util/AsyncCombinedLock.java:29`
- `c2me-base/src/main/java/com/ishland/c2me/base/common/scheduler/SchedulingAsyncCombinedLock.java:48`

**Severity**: HIGH
**Difficulty**: HARD
**Impact**: Performance - Lock contention on hot path

**Description**:
Both `AsyncCombinedLock` and `SchedulingAsyncCombinedLock` use `synchronized void tryAcquire()` which acquires full method-level synchronization. This serializes all lock acquisition attempts across all chunks, creating a bottleneck on highly concurrent workloads.

The TODO comment "optimize logic further" indicates this is known technical debt.

Current pattern:
```java
synchronized boolean tryAcquire() {  // BLOCKS ALL OTHER ACQUIRES
    // Try to acquire multiple locks
    // Individual tryLock operations are already thread-safe
    // ...
}
```

**Problem**:
- The underlying `AsyncNamedLock.tryLock()` operations are already thread-safe
- Method-level sync prevents parallel lock acquisition for different chunk sets
- Creates artificial serialization point in concurrent system

**Suggested Fix**:
Replace with fine-grained locking or lock-free state machine:

```java
private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

boolean tryAcquire() {
    if (!state.compareAndSet(State.PENDING, State.ACQUIRING)) {
        return false;  // Already being acquired
    }

    try {
        // Existing lock acquisition logic without synchronization
        // Individual tryLock operations provide necessary atomicity
    } finally {
        state.set(State.ACQUIRED_OR_FAILED);
    }
}
```

**Expected Benefit**: Removes serialization bottleneck. Could dramatically improve throughput on multi-core systems with high chunk loading concurrency.

---

### Issue 2.2: Spinning Retry Without Backoff

**File**: `c2me-base/src/main/java/com/ishland/c2me/base/common/util/AsyncCombinedLock.java`
**Lines**: 54-65
**Severity**: HIGH
**Difficulty**: MEDIUM
**Impact**: Performance - CPU waste under contention

**Description**:
When lock acquisition fails, the code immediately schedules a retry via `CompletableFuture.runAsync(this::tryAcquire)` without backoff. Under high contention, this creates spinning behavior where failed attempts immediately queue more work, wasting CPU cycles.

```java
if (!triedRelock) {
    // shouldn't happen at all...
    CompletableFuture.runAsync(this::tryAcquire, GlobalExecutors.scheduler);
    // NO BACKOFF - immediately retries!
}
```

**Suggested Fix**:
Implement exponential backoff:

```java
private final AtomicInteger retryCount = new AtomicInteger(0);

private void scheduleRetryWithBackoff() {
    int attempts = retryCount.incrementAndGet();
    long delayNanos = Math.min(1_000_000L * (1L << attempts), 100_000_000L); // Cap at 100ms

    GlobalExecutors.scheduler.schedule(
        () -> {
            if (tryAcquire()) {
                retryCount.set(0);  // Reset on success
            }
        },
        delayNanos,
        TimeUnit.NANOSECONDS
    );
}
```

**Expected Benefit**: Reduces CPU waste during lock contention. Improves overall system throughput under load.

---

### Issue 3.1: Race Condition in Lock Token Release

**File**: `c2me-base/src/main/java/com/ishland/c2me/base/common/util/AsyncCombinedLock.java`
**Lines**: 48-66
**Severity**: HIGH
**Difficulty**: HARD
**Impact**: Correctness - Potential synchronization violation

**Description**:
In the "relock path," when one lock fails to acquire, the code releases already-acquired locks and then schedules a retry. There's a race window where:

1. Lock token is released: `lockToken.releaseLock()`
2. Another thread acquires that lock
3. Retry executes and tries to acquire locks again
4. The lock might now be held by different owner

```java
entry.lockToken.ifPresent(AsyncLock.LockToken::releaseLock);  // Release immediately
if (!triedRelock && entry.lockToken.isEmpty()) {
    this.lock.acquireLock(entry.name).thenAccept(lockToken -> {
        lockToken.releaseLock();  // But this happens LATER
        CompletableFuture.runAsync(this::tryAcquire, GlobalExecutors.scheduler);
    });
}
```

**Problem**:
The timing between release and retry scheduling creates a window where lock ownership assumptions could be violated.

**Suggested Fix**:
Don't release locks until retry is actually scheduled and executing. Use event-driven notification from the async lock library instead of polling retries.

**Expected Benefit**: Eliminates potential race condition. Ensures correct synchronization semantics.

---

## TIER 3: Medium Impact

### Issue 1.1: DynamicPriorityQueue Empty Bucket Iteration

**File**: `c2me-base/src/main/java/com/ishland/c2me/base/common/structs/DynamicPriorityQueue.java`
**Lines**: 55-67
**Severity**: MEDIUM
**Difficulty**: MEDIUM
**Impact**: Performance - O(n) worst-case dequeue

**Description**:
The `dequeue()` method has O(priorities.length) worst-case when many buckets are empty. It must linearly scan from `currentMinPriority` upward to find the next non-empty bucket.

```java
while (currentMinPriority < priorities.length) {
    final ObjectLinkedOpenHashSet<T> set = priorities[currentMinPriority];
    if (set != null && !set.isEmpty()) {
        final T first = set.removeFirst();
        return first;
    }
    currentMinPriority++;  // Linear scan through empties
}
```

**Suggested Fix**:
Track non-empty buckets using bit manipulation or skip list:

```java
private long nonEmptyBuckets = 0L;  // For up to 64 priority levels

public void enqueue(T task, int priority) {
    // ... existing logic ...
    nonEmptyBuckets |= (1L << priority);  // Mark as non-empty
}

public T dequeue() {
    if (nonEmptyBuckets == 0) return null;

    int priority = Long.numberOfTrailingZeros(nonEmptyBuckets);  // O(1) find
    ObjectLinkedOpenHashSet<T> set = priorities[priority];
    T first = set.removeFirst();

    if (set.isEmpty()) {
        nonEmptyBuckets &= ~(1L << priority);  // Clear bit
    }

    return first;
}
```

**Expected Benefit**: O(1) dequeue instead of O(n). More consistent latency.

---

### Issue 2.8: Atomic Counter Contention in C2MEStorageThread

**File**: `c2me-rewrites-chunkio/src/main/java/com/ishland/c2me/rewrites/chunkio/common/C2MEStorageThread.java`
**Lines**: 163-172, 189-197, 207-216
**Severity**: MEDIUM
**Difficulty**: MEDIUM
**Impact**: Performance - Atomic contention on I/O operations

**Description**:
Every `getChunkData()` and `setChunkData()` call increments atomic integers (`readQueueSize`, `writeQueueSize`) and checks thresholds. Under high I/O throughput, this creates contention on the atomic variables.

```java
readQueueSize.incrementAndGet();  // Atomic operation on every read
if (readQueueSize.get() > 8192) {
    // Throttled warning...
}
```

**Suggested Fix**:
Use sampling or thread-local counters with periodic aggregation:

```java
private static final ThreadLocal<Integer> localReadCount = ThreadLocal.withInitial(() -> 0);
private static final int SAMPLE_RATE = 100;

public CompletableFuture<Optional<NbtCompound>> getChunkData(ChunkPos pos) {
    int count = localReadCount.get() + 1;
    if (count % SAMPLE_RATE == 0) {
        readQueueSize.addAndGet(SAMPLE_RATE);
        localReadCount.set(0);
    } else {
        localReadCount.set(count);
    }
    // ... rest of method ...
}
```

**Expected Benefit**: Reduces atomic contention. Better scalability on high I/O throughput.

---

### Issue 1.2: ListIndexedIterable Linear Search

**File**: `c2me-base/src/main/java/com/ishland/c2me/base/common/util/ListIndexedIterable.java`
**Line**: 14
**Severity**: MEDIUM
**Difficulty**: EASY-MEDIUM
**Impact**: Performance - O(n) lookup where O(1) possible

**Description**:
`getRawId()` calls `delegate.indexOf(entry)` which is O(n) for ArrayList.

```java
@Override
public int getRawId(T entry) {
    return this.delegate.indexOf(entry);  // O(n) linear search
}
```

**Suggested Fix**:
Check if delegate is RandomAccess (ArrayList) and use identity comparison:

```java
@Override
public int getRawId(T entry) {
    if (delegate instanceof RandomAccess) {
        for (int i = 0; i < delegate.size(); i++) {
            if (delegate.get(i) == entry) return i;  // O(1) indexed access
        }
        return -1;
    }
    return this.delegate.indexOf(entry);
}
```

Or maintain a reverse index map.

**Expected Benefit**: O(1) lookup for ArrayList-backed iterables.

---

### Issue 1.3: Redundant Distance Calculations

**File**: `c2me-base/src/main/java/com/ishland/c2me/base/common/scheduler/SchedulingManager.java`
**Lines**: 106-114
**Severity**: LOW
**Difficulty**: MEDIUM
**Impact**: Performance - Redundant computation

**Description**:
`updateSyncLoadInternal()` iterates through a 17×17 grid calling `updatePriorityInternal()` which computes Chebyshev distance. If this distance is computed elsewhere, it's redundant.

**Suggested Fix**:
Cache computed distances or batch-compute for all chunks.

**Expected Benefit**: Minor CPU reduction on sync load updates.

---

## TIER 4: Lower Priority Issues

### Issue 2.6: NeighborLockingManager Pool Overhead

**File**: `c2me-base/src/main/java/com/ishland/c2me/base/common/scheduler/NeighborLockingManager.java`
**Severity**: MEDIUM
**Difficulty**: MEDIUM

Pool allocation for ReferenceArraySet creates contention. Consider pre-sizing or using more efficient callback structure.

---

### Issue 2.7: Stream Operations in AsyncSerializationManager

**File**: `c2me-threading-chunkio/src/main/java/com/ishland/c2me/threading/chunkio/common/AsyncSerializationManager.java`
**Severity**: LOW
**Difficulty**: EASY

Minor allocation pressure from default values. Verify EMPTY singleton usage.

---

### Issue 3.2: NullPointerException Risk in NeighborLockingManager

**File**: `c2me-base/src/main/java/com/ishland/c2me/base/common/scheduler/NeighborLockingManager.java`
**Lines**: 34-50
**Severity**: LOW
**Difficulty**: EASY

Add null check or clearer documentation about thread safety requirements.

---

### Issue 3.3: Missing Null Check in DynamicPriorityQueue

**File**: `c2me-base/src/main/java/com/ishland/c2me/base/common/structs/DynamicPriorityQueue.java`
**Severity**: MEDIUM
**Difficulty**: EASY

Document nullable return or throw NoSuchElementException.

---

### Issue 3.4: SMAPSourceDebugExtension Thread Safety

**File**: `c2me-fixes-worldgen-threading-issues/src/main/java/com/ishland/c2me/fixes/worldgen/threading_issues/common/debug/SMAPSourceDebugExtension.java`
**Severity**: MEDIUM
**Difficulty**: MEDIUM

Review for race conditions during concurrent class loading.

---

### Issue 4.1: Suppressed Warnings

**Files**: AsyncCombinedLock.java, SchedulingAsyncCombinedLock.java
**Severity**: LOW
**Difficulty**: EASY

Replace `Optional.get()` with `orElseThrow()`.

---

### Issue 4.4: Overly Broad Exception Handling

**File**: `c2me-rewrites-chunkio/src/main/java/com/ishland/c2me/rewrites/chunkio/common/C2MEStorageThread.java`
**Severity**: MEDIUM
**Difficulty**: EASY-MEDIUM

Catch specific exceptions instead of Throwable. Re-throw Error subclasses.

---

### Issue 4.5: Complex Nested Conditionals

**File**: `c2me-threading-chunkio/src/main/java/com/ishland/c2me/threading/chunkio/mixin/MixinThreadedAnvilChunkStorage.java`
**Severity**: MEDIUM
**Difficulty**: MEDIUM-HARD

Large mixin file needs manual review for complexity.

---

### Issue 4.6: Incomplete Error Handling

**File**: `c2me-rewrites-chunkio/src/main/java/com/ishland/c2me/rewrites/chunkio/common/C2MEStorageThread.java`
**Severity**: HIGH
**Difficulty**: HARD

Multiple TODO comments for error retry logic. Implement comprehensive error handling.

---

## Implementation Priority

**Phase 1** (Tier 1 - Quick Wins):
1. Issue 2.3: CMETrackingMap Throwable allocation
2. Issue 2.5: Function.identity() removal
3. Issue 2.4: Dead timing code
4. Issue 4.3: @Nullable annotations
5. Issue 3.5: ThreadLocal leak fix

**Phase 2** (Tier 2 - High Value, Hard):
6. Issue 2.1: Synchronization bottleneck
7. Issue 2.2: Retry backoff
8. Issue 3.1: Lock token race condition

**Phase 3** (Tier 3+):
9. Remaining medium/low priority issues

---

## Testing Strategy

For each fix:
1. Unit test the specific component
2. Run full gradle build
3. Load test with chunk generation workload
4. Profile to verify performance improvement
5. Monitor for regressions

---

## Risk Assessment

**Low Risk** (safe to implement immediately):
- Issues 2.3, 2.4, 2.5, 4.2, 4.3

**Medium Risk** (needs testing):
- Issues 3.5, 1.1, 2.8

**High Risk** (needs careful review and testing):
- Issues 2.1, 2.2, 3.1

---

End of Audit Document
