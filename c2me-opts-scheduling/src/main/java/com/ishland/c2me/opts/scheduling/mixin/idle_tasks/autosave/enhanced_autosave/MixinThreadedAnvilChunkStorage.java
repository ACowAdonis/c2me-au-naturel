package com.ishland.c2me.opts.scheduling.mixin.idle_tasks.autosave.enhanced_autosave;

import com.ishland.c2me.opts.scheduling.common.Config;
import com.ishland.c2me.opts.scheduling.common.idle_tasks.IThreadedAnvilChunkStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.CompletableFuture;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class MixinThreadedAnvilChunkStorage implements IThreadedAnvilChunkStorage {

    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> currentChunkHolders;

    @Shadow protected abstract boolean save(ChunkHolder chunkHolder);

    @Unique
    private final Object2LongLinkedOpenHashMap<ChunkPos> dirtyChunkPosForAutoSave = new Object2LongLinkedOpenHashMap<>();

    @Override
    public void enqueueDirtyChunkPosForAutoSave(ChunkPos chunkPos) {
        if (chunkPos == null) {
            return;
        }
        synchronized (this.dirtyChunkPosForAutoSave) {
            this.dirtyChunkPosForAutoSave.putAndMoveToLast(chunkPos, System.currentTimeMillis());
        }
    }

    @Override
    public boolean runOneChunkAutoSave() {
        // C2ME fix: Reduce lock scope to prevent holding lock during I/O operations
        ChunkPos chunkPos = null;

        // Only hold lock while accessing the queue structure
        synchronized (this.dirtyChunkPosForAutoSave) {
            final ObjectBidirectionalIterator<Object2LongMap.Entry<ChunkPos>> iterator = this.dirtyChunkPosForAutoSave.object2LongEntrySet().fastIterator();
            while (iterator.hasNext()) {
                final Object2LongMap.Entry<ChunkPos> entry = iterator.next();
                long timestamp = entry.getLongValue();
                if (System.currentTimeMillis() - timestamp < Config.autoSaveDelayMillis) {
                    return false; // Nothing ready to save yet
                }
                chunkPos = entry.getKey();
                iterator.remove(); // Remove from queue
                if (chunkPos != null) {
                    break; // Found one to process, exit synchronized block
                }
                // If null, continue loop to find next valid entry
            }
        }

        // Process outside the lock to avoid blocking other operations
        if (chunkPos == null) {
            return false; // Queue empty or only had null entries
        }

        ChunkHolder chunkHolder = this.currentChunkHolders.get(chunkPos.toLong());
        if (chunkHolder == null) {
            return false; // Chunk already unloaded
        }

        final CompletableFuture<Chunk> savingFuture = chunkHolder.getSavingFuture();
        if (savingFuture.isDone()) {
            this.save(chunkHolder); // I/O operation outside lock
            return true;
        } else {
            // Re-enqueue for later if still saving
            savingFuture.handle((chunk, throwable) -> {
                this.enqueueDirtyChunkPosForAutoSave(chunkHolder.getPos());
                return null;
            });
            return false;
        }
    }
}
