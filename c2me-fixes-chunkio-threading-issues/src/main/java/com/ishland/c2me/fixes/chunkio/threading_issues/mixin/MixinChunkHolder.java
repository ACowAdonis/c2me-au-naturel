package com.ishland.c2me.fixes.chunkio.threading_issues.mixin;

import com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(ThreadedAnvilChunkStorage.class)
public class MixinChunkHolder {

    // Publish pending holder-map changes for async readers once per TACS tick.
    // The previous per-ChunkHolder-tick refresh re-cloned the entire holder map
    // for every holder whenever the dirty flag was set - O(holders^2) during
    // load/unload storms. Once per tick restores vanilla cost; async readers
    // tolerate snapshot staleness by design (a miss falls back to the
    // main-thread path).
    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void beforeTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        ((IThreadedAnvilChunkStorage) this).invokeUpdateHolderMap();
    }

}
