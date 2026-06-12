package com.ishland.c2me.threading.chunkio.mixin;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.gen.chunk.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Blender.class)
public class MixinBlender {

    // Blending (pre-1.18 terrain border smoothing) never activates on the fresh
    // 1.20.1 worlds this fork targets, and vanilla's check here reads neighbor
    // chunks unsafely under reduced lock radius. Answer false unconditionally:
    // every chunk takes the no-blending fast path. On a world upgraded from
    // pre-1.18 this means seams at old/new terrain borders (graceful, no
    // corruption).
    @Redirect(method = "getBlender", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ChunkRegion;needsBlending(Lnet/minecraft/util/math/ChunkPos;I)Z"))
    private static boolean redirectNeedsBlending(ChunkRegion instance, ChunkPos chunkPos, int checkRadius) {
        return false;
    }

}
