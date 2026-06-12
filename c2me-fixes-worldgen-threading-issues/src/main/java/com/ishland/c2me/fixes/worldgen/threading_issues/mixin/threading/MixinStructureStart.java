package com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import net.minecraft.structure.StructurePiecesList;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.structure.Structure;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

@Mixin(StructureStart.class)
public class MixinStructureStart {

    private final AtomicInteger referencesAtomic = new AtomicInteger();

    // All reads of the references field are redirected to the atomic, but the
    // constructor (NBT load path) writes the real field - without mirroring the
    // ctor value here, every structure reloads with references == 0 and re-saves
    // the zero, permanently resetting persisted reference counts.
    @Inject(method = "<init>(Lnet/minecraft/world/gen/structure/Structure;Lnet/minecraft/util/math/ChunkPos;ILnet/minecraft/structure/StructurePiecesList;)V", at = @At("RETURN"))
    private void onInit(Structure structure, ChunkPos pos, int references, StructurePiecesList children, CallbackInfo ci) {
        this.referencesAtomic.set(references);
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/structure/StructureStart;references:I", opcode = Opcodes.GETFIELD))
    private int redirectGetReferences(StructureStart structureStart) {
        return referencesAtomic.get();
    }

    /**
     * @author ishland
     * @reason atomic operation
     */
    @Overwrite
    public void incrementReferences() {
        this.referencesAtomic.incrementAndGet();
    }

}
