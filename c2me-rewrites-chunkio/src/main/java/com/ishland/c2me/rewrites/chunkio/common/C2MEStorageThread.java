package com.ishland.c2me.rewrites.chunkio.common;

import com.ibm.asyncutil.util.Either;
import com.ishland.c2me.base.common.GlobalExecutors;
import com.ishland.c2me.base.common.structs.RawByteArrayOutputStream;
import com.ishland.c2me.base.common.util.SneakyThrow;
import com.ishland.c2me.base.mixin.access.IRegionBasedStorage;
import com.ishland.c2me.base.mixin.access.IRegionFile;
import com.ishland.c2me.opts.chunkio.common.ConfigConstants;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.scanner.NbtScanner;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionBasedStorage;
import net.minecraft.world.storage.RegionFile;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

public class C2MEStorageThread extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger("C2ME Storage");

    private static final AtomicLong SERIAL = new AtomicLong(0);

    // C2ME fix: Queue size limits to prevent unbounded memory growth
    private static final int MAX_READ_QUEUE_SIZE = 8192;
    private static final int MAX_WRITE_QUEUE_SIZE = 8192;
    private static final int WARN_READ_QUEUE_SIZE = 4096;
    private static final int WARN_WRITE_QUEUE_SIZE = 4096;

    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    // bounded retries for failed region-file writes; storage-thread confined
    private static final int MAX_WRITE_RETRIES = 3;

    private final RegionBasedStorage storage;
    private final Long2ReferenceLinkedOpenHashMap<Either<NbtCompound, byte[]>> writeBacklog = new Long2ReferenceLinkedOpenHashMap<>();
    private final Long2ReferenceLinkedOpenHashMap<Either<NbtCompound, byte[]>> cache = new Long2ReferenceLinkedOpenHashMap<>();
    private final Long2IntOpenHashMap writeRetryCounts = new Long2IntOpenHashMap();
    private final ConcurrentLinkedQueue<ReadRequest> pendingReadRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<WriteRequest> pendingWriteRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();
    private final Executor executor = command -> {
        if (Thread.currentThread() == this) {
            command.run();
        } else {
            pendingTasks.add(command);
            // C2ME fix: Always wake up to avoid lost wakeup race condition
            // The previous optimization (only wake if queue was empty) had a race:
            // producer checks empty->false, storage thread drains queue, producer adds,
            // producer skips wakeUp, storage thread sleeps with work pending
            this.wakeUp();
        }
    };
    private final ObjectArraySet<CompletableFuture<Void>> writeFutures = new ObjectArraySet<>();
    private final Object sync = new Object();

    // C2ME fix: Monitoring fields to track queue sizes and warn about growth
    private final AtomicInteger readQueueSize = new AtomicInteger(0);
    private final AtomicInteger writeQueueSize = new AtomicInteger(0);
    private volatile long lastQueueWarningTime = 0;

    public C2MEStorageThread(Path directory, boolean dsync, String name) {
        this.storage = new RegionBasedStorage(directory, dsync);
        this.setName("C2ME Storage #%d".formatted(SERIAL.incrementAndGet()));
        this.setDaemon(true);
        this.setUncaughtExceptionHandler((t, e) -> LOGGER.error("Thread %s died".formatted(t), e));
        this.start();
    }

    @Override
    public void run() {
        main_loop:
        while (true) {
            boolean hasWork = false;
            hasWork |= pollTasks();

            runWriteFutureGC();

            if (!hasWork) {
                if (this.closing.get()) {
                    flush0(true);
                    // requests that arrived during the flush: go around again
                    if (this.hasPendingTasks()) continue;
                    try {
                        this.storage.close();
                    } catch (Throwable t) {
                        LOGGER.error("Error closing storage", t);
                    }
                    // post-close sweep: late stragglers must not hang (reads) or
                    // vanish silently (writes)
                    ReadRequest readRequest;
                    while ((readRequest = this.pendingReadRequests.poll()) != null) {
                        readQueueSize.decrementAndGet();
                        readRequest.future().completeExceptionally(new CancellationException("Storage closed"));
                    }
                    int droppedWrites = 0;
                    while (this.pendingWriteRequests.poll() != null) {
                        writeQueueSize.decrementAndGet();
                        droppedWrites++;
                    }
                    if (droppedWrites > 0) {
                        LOGGER.error("{} chunk write(s) submitted after storage close were DROPPED", droppedWrites);
                    }
                    this.closeFuture.complete(null);
                    break;
                } else {
                    // attempt to spin-wait before sleeping
                    if (!pollTasks()) {
                        Thread.interrupted(); // clear interrupt flag
                        for (int i = 0; i < 5000; i ++) {
                            if (pollTasks()) continue main_loop;
                            LockSupport.parkNanos("Spin-waiting for tasks", 10_000); // 100us
                        }
                    }
                    synchronized (sync) {
                        if (this.hasPendingTasks() || this.closing.get()) continue main_loop;
                        try {
                            sync.wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }
        LOGGER.info("Storage thread {} stopped", this);
    }

    private boolean pollTasks() {
        boolean hasWork = false;
        hasWork = handleTasks() || hasWork;
        // Writes must be moved into the cache before any read is served:
        // the cache is the only read-your-writes mechanism, so serving a read
        // for a pos with an un-intaken write would load stale data from disk
        // that later re-saves over the newer state. Intake does no disk I/O;
        // read prioritization is provided by the writeBacklog budget below.
        hasWork = handlePendingWrites() || hasWork;
        hasWork = handlePendingReads() || hasWork;
        // Reads are latency-sensitive (the game is waiting on them); disk writes are
        // not, because the cache serves read-your-writes for unflushed data. So writes
        // yield to reads — but as a bounded throttle, never a lockout: under sustained
        // read load the backlog must still drain, or it grows without bound and every
        // backlogged chunk is lost on crash. Past the force-flush threshold the read
        // gating is ignored entirely.
        final int writeBudget = (pendingReadRequests.isEmpty() || this.writeBacklog.size() >= BACKLOG_FORCE_FLUSH_SIZE)
                ? MAX_WRITES_PER_CYCLE : MIN_WRITES_PER_CYCLE_UNDER_READS;
        hasWork = writeBacklog(writeBudget) || hasWork;
        return hasWork;
    }

    private boolean hasPendingTasks() {
        return !this.pendingTasks.isEmpty() || !this.pendingReadRequests.isEmpty() || !this.pendingWriteRequests.isEmpty() || !this.writeBacklog.isEmpty();
    }

    private void wakeUp() {
        synchronized (sync) {
            sync.notifyAll();
        }
    }

    /**
     * Read chunk data from storage
     * @param pos target pos
     * @param scanner if null then ignored, if non-null then used and produce null future
     * @return future
     */
    public CompletableFuture<NbtCompound> getChunkData(long pos, NbtScanner scanner) {
        final CompletableFuture<NbtCompound> future = new CompletableFuture<>();
        if (this.closing.get()) {
            future.completeExceptionally(new CancellationException());
            return future;
        }

        // C2ME fix: Check queue size and warn about potential memory issues
        final int currentReadQueueSize = readQueueSize.incrementAndGet();
        if (currentReadQueueSize > MAX_READ_QUEUE_SIZE) {
            LOGGER.error("Read request queue size ({}) exceeded maximum ({}). Storage thread may be overloaded! Chunk: {}", currentReadQueueSize, MAX_READ_QUEUE_SIZE, new ChunkPos(pos));
        } else if (currentReadQueueSize > WARN_READ_QUEUE_SIZE) {
            final long now = System.currentTimeMillis();
            if (now - lastQueueWarningTime > 5000) { // Throttle warnings to once per 5 seconds
                LOGGER.warn("Read request queue size ({}) is high. Storage thread may be experiencing heavy load. Consider reducing worldgen speed.", currentReadQueueSize);
                lastQueueWarningTime = now;
            }
        }

        this.pendingReadRequests.add(new ReadRequest(pos, future, scanner));
        // C2ME fix: Always wake up to avoid lost wakeup race condition
        this.wakeUp();
        future.thenApply(Function.identity()).orTimeout(60, TimeUnit.SECONDS).exceptionally(throwable -> {
            if (throwable instanceof TimeoutException) {
                LOGGER.warn("Chunk read at pos {} took too long (> 1min)", new ChunkPos(pos).toLong());
            }
            return null;
        });
        return future;
    }

    public void setChunkData(long pos, @Nullable NbtCompound nbt) {
        writeQueueSize.incrementAndGet();
        warnWriteAccumulation(pos);
        this.pendingWriteRequests.add(new WriteRequest(pos, nbt != null ? Either.left(nbt) : null));
        // C2ME fix: Always wake up to avoid lost wakeup race condition
        this.wakeUp();
    }

    public void setChunkData(long pos, @Nullable byte[] data) {
        writeQueueSize.incrementAndGet();
        warnWriteAccumulation(pos);
        this.pendingWriteRequests.add(new WriteRequest(pos, data != null ? Either.right(data) : null));
        // C2ME fix: Always wake up to avoid lost wakeup race condition
        this.wakeUp();
    }

    /**
     * Warn when serialized chunk data accumulates in memory. The transit queue
     * (pendingWriteRequests) drains within one poll cycle; the real accumulation
     * under a slow disk is writeBacklog plus in-flight writeFutures. Those are
     * storage-thread-confined structures — the size() reads here from caller
     * threads are racy but harmless for monitoring purposes.
     */
    private void warnWriteAccumulation(long pos) {
        final int accumulated = writeQueueSize.get() + this.writeBacklog.size() + this.writeFutures.size();
        if (accumulated > MAX_WRITE_QUEUE_SIZE) {
            final long now = System.currentTimeMillis();
            if (now - lastQueueWarningTime > 5000) {
                LOGGER.error("{} serialized chunks held in memory awaiting disk writes (limit {}). Disk cannot keep up; data-loss window on crash is growing. Chunk: {}", accumulated, MAX_WRITE_QUEUE_SIZE, new ChunkPos(pos));
                lastQueueWarningTime = now;
            }
        } else if (accumulated > WARN_WRITE_QUEUE_SIZE) {
            final long now = System.currentTimeMillis();
            if (now - lastQueueWarningTime > 5000) {
                LOGGER.warn("{} serialized chunks held in memory awaiting disk writes. Storage may be experiencing heavy load.", accumulated);
                lastQueueWarningTime = now;
            }
        }
    }

    public CompletableFuture<Void> flush(boolean sync) {
        // after thread exit nothing drains pendingTasks; everything was already
        // flushed during close, so don't strand the caller on a dead queue
        if (this.closeFuture.isDone()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> flush0(sync), this.executor);
    }

    private void flush0(boolean sync) {
        try {
            while (true) {
                runWriteFutureGC();
                if (handleTasks()) continue;
                // write intake before reads: same read-your-writes ordering as pollTasks
                if (handlePendingWrites()) continue;
                if (handlePendingReads()) continue;
                if (writeBacklog()) continue;

                break;
            }
            flushBacklog();
            if (sync) this.storage.sync();
        } catch (Throwable t) {
            LOGGER.error("Error flushing storage", t);
        }
    }

    public CompletableFuture<Void> close() {
        this.closing.set(true);
        this.wakeUp();
        return this.closeFuture;
    }

    private boolean handleTasks() {
        boolean hasWork = false;
        Runnable runnable;
        while ((runnable = this.pendingTasks.poll()) != null) {
            hasWork = true;
            try {
                runnable.run();
            } catch (Throwable t) {
                LOGGER.error("Error while executing task", t);
            }
        }
        return hasWork;
    }

    private boolean handlePendingWrites() {
        boolean hasWork = false;
        WriteRequest writeRequest;
        while ((writeRequest = this.pendingWriteRequests.poll()) != null) {
            hasWork = true;
            // C2ME fix: Decrement queue size counter
            writeQueueSize.decrementAndGet();
            this.cache.put(writeRequest.pos, writeRequest.nbt);
            this.writeBacklog.put(writeRequest.pos, writeRequest.nbt);
        }
        return hasWork;
    }

    private boolean handlePendingReads() {
        boolean hasWork = false;
        while (!pendingReadRequests.isEmpty()) {
            // This drain loop keeps consuming reads that arrive while it runs,
            // so writes enqueued before those reads must be intaken first to
            // keep the cache's read-your-writes guarantee.
            handlePendingWrites();
            ReadRequest readRequest = this.pendingReadRequests.poll();
            hasWork = true;
            // C2ME fix: Decrement queue size counter
            readQueueSize.decrementAndGet();
            assert readRequest != null;
            final long pos = readRequest.pos;
            final CompletableFuture<NbtCompound> future = readRequest.future;
            final NbtScanner scanner = readRequest.scanner;
            // Single lookup optimization: get first, then only check containsKey for null case
            final Either<NbtCompound, byte[]> cached = this.cache.get(pos);
            if (cached != null) {
                if (cached.left().isPresent()) {
                    if (scanner != null) {
                        GlobalExecutors.executor.execute(() -> {
                            try {
                                cached.left().get().accept(scanner);
                                future.complete(null);
                            } catch (Throwable t) {
                                future.completeExceptionally(t);
                            }
                        });
                    } else {
                        future.complete(cached.left().get());
                    }
                } else {
                    CompletableFuture.supplyAsync(() -> {
                                try {
                                    final DataInputStream input = new DataInputStream(new ByteArrayInputStream(cached.right().get()));
                                    if (scanner != null) {
                                        NbtIo.scan(input, scanner);
                                        return null;
                                    } else {
                                        final NbtCompound compound = NbtIo.read(input);
                                        return compound;
                                    }
                                } catch (IOException e) {
                                    SneakyThrow.sneaky(e);
                                    return null; // unreachable
                                }
                            }, GlobalExecutors.executor)
                            .thenAccept(future::complete)
                            .exceptionally(throwable -> {
                                future.completeExceptionally(throwable);
                                return null;
                            });
                }
                continue;
            } else if (this.cache.containsKey(pos)) {
                // Key exists but value is null - chunk is known to not exist
                future.complete(null);
                continue;
            }
            scheduleChunkRead(pos, future, scanner);
        }
        return hasWork;
    }

    // Maximum chunks to write per poll cycle when no reads are pending
    private static final int MAX_WRITES_PER_CYCLE = 8;
    // Guaranteed write progress per cycle while reads are pending (throttle, not lockout)
    private static final int MIN_WRITES_PER_CYCLE_UNDER_READS = 1;
    // Backlog size beyond which read gating is ignored and writes flush at full rate
    private static final int BACKLOG_FORCE_FLUSH_SIZE = 1024;

    private boolean writeBacklog() {
        return writeBacklog(MAX_WRITES_PER_CYCLE);
    }

    private boolean writeBacklog(int budget) {
        if (this.writeBacklog.isEmpty()) {
            return false;
        }

        int written = 0;
        while (!this.writeBacklog.isEmpty() && written < budget) {
            final long pos = this.writeBacklog.firstLongKey();
            final Either<NbtCompound, byte[]> nbt = this.writeBacklog.removeFirst();
            writeChunk(pos, nbt);
            written++;
        }
        return true;
    }

    private void runWriteFutureGC() {
        this.writeFutures.removeIf(CompletableFuture::isDone);
    }

    private void flushBacklog() {
        while (!this.writeFutures.isEmpty()) {
            while (writeBacklog()) ;
            runWriteFutureGC();
            final CompletableFuture<Void> allFuture = CompletableFuture.allOf(this.writeFutures.stream()
                    .map(future -> future.exceptionally(unused -> null))
                    .distinct()
                    .toArray(CompletableFuture[]::new));
            while (!allFuture.isDone()) {
                handleTasks();
            }
            runWriteFutureGC();
        }
    }

    private void scheduleChunkRead(long pos, CompletableFuture<NbtCompound> future, NbtScanner scanner) {
        try {
            final ChunkPos pos1 = new ChunkPos(pos);
            final RegionFile regionFile = ((IRegionBasedStorage) this.storage).invokeGetRegionFile(pos1);
            final DataInputStream chunkInputStream = regionFile.getChunkInputStream(pos1);
            if (chunkInputStream == null) {
                future.complete(null);
                return;
            }
            CompletableFuture.supplyAsync(() -> {
                try {
                    try (DataInputStream inputStream = chunkInputStream) {
                        if (scanner != null) {
                            NbtIo.scan(inputStream, scanner);
                            return null;
                        } else {
                            return NbtIo.read(inputStream);
                        }
                    }
                } catch (Throwable t) {
                    SneakyThrow.sneaky(t);
                    return null; // Unreachable anyway
                }
            }, GlobalExecutors.executor).handle((compound, throwable) -> {
                if (throwable != null) future.completeExceptionally(throwable);
                else future.complete(compound);
                return null;
            });
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }

    private void writeChunk(long pos, Either<NbtCompound, byte[]> nbt) {
        if (nbt == null) {
            if (this.cache.get(pos) == null) {
                try {
                    final ChunkPos pos1 = new ChunkPos(pos);
                    final RegionFile regionFile = ((IRegionBasedStorage) this.storage).invokeGetRegionFile(pos1);
                    regionFile.delete(pos1);
                } catch (Throwable t) {
                    LOGGER.error("Error writing chunk %s".formatted(new ChunkPos(pos)), t);
                }
                this.cache.remove(pos);
            }
        } else {
            final CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                try {
                    final RawByteArrayOutputStream out = new RawByteArrayOutputStream(8096);
                    // TODO [VanillaCopy] RegionFile.ChunkBuffer
                    out.write(0);
                    out.write(0);
                    out.write(0);
                    out.write(0);
                    out.write(ConfigConstants.CHUNK_STREAM_VERSION.getId());
                    try (DataOutputStream dataOutputStream = new DataOutputStream(ConfigConstants.CHUNK_STREAM_VERSION.wrap(out))) {
                        if (nbt.left().isPresent()) {
                            NbtIo.write(nbt.left().get(), dataOutputStream);
                        } else {
                            dataOutputStream.write(nbt.right().get());
                        }
                    }
                    return out;
                } catch (Throwable t) {
                    SneakyThrow.sneaky(t);
                    return null; // Unreachable anyway
                }
            }, GlobalExecutors.executor).thenAcceptAsync(bytes -> {
                if (nbt == this.cache.get(pos)) { // only write if match to avoid overwrites
                    try {
                        final ChunkPos pos1 = new ChunkPos(pos);
                        final RegionFile regionFile = ((IRegionBasedStorage) this.storage).invokeGetRegionFile(pos1);
                        ByteBuffer byteBuffer = bytes.asByteBuffer();
                        // TODO [VanillaCopy] RegionFile.ChunkBuffer
                        byteBuffer.putInt(0, bytes.size() - 5 + 1);
                        ((IRegionFile) regionFile).invokeWriteChunk(pos1, byteBuffer);
                    } catch (Throwable t) {
                        SneakyThrow.sneaky(t);
                    }
                    this.cache.remove(pos);
                }
            }, this.executor).handleAsync((unused, throwable) -> {
                // runs on the storage thread: backlog/cache/retry state is safe to touch
                if (throwable != null) {
                    if (nbt == this.cache.get(pos)) { // still the newest data for this pos
                        final int retries = this.writeRetryCounts.addTo(pos, 1) + 1;
                        if (retries <= MAX_WRITE_RETRIES) {
                            LOGGER.warn("Error writing chunk {}, re-queueing (attempt {}/{})", new ChunkPos(pos), retries, MAX_WRITE_RETRIES, throwable);
                            this.writeBacklog.put(pos, nbt);
                        } else {
                            this.writeRetryCounts.remove(pos);
                            // keep the cache entry: readers continue to see the newest
                            // data even though the disk copy is stale
                            LOGGER.error("Failed to write chunk {} after {} attempts; data is retained in memory but the on-disk copy is STALE", new ChunkPos(pos), MAX_WRITE_RETRIES, throwable);
                        }
                    } else {
                        // superseded by a newer write; that write carries its own retries
                        this.writeRetryCounts.remove(pos);
                        LOGGER.warn("Error writing chunk {} (superseded by a newer write)", new ChunkPos(pos), throwable);
                    }
                } else {
                    this.writeRetryCounts.remove(pos);
                }
                return null;
            }, this.executor);
            this.writeFutures.add(future);
        }
    }

    private record ReadRequest(long pos, CompletableFuture<NbtCompound> future, @Nullable NbtScanner scanner) {
    }

    private record WriteRequest(long pos, Either<NbtCompound, byte[]> nbt) {
    }

}
