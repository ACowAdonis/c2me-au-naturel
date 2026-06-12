package com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import net.minecraft.structure.DesertTempleGenerator;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(DesertTempleGenerator.class)
public abstract class MixinDesertTempleGenerator {

    // Slots must be primed FALSE before any constructor body runs (mixin field
    // initializers execute after the super call, ahead of the ctor body): the
    // redirected ctor writes restore NBT state via compareAndSet(index, false, value),
    // which fails against a null slot - a temple saved with a chest placed would
    // reload as unplaced and duplicate its loot when generation resumes.
    private final AtomicReferenceArray<Boolean> hasPlacedChestAtomic =
            new AtomicReferenceArray<>(new Boolean[]{Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE});

    @Dynamic
    @SuppressWarnings({"InvalidInjectorMethodSignature", "RedundantSuppression"})
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/structure/DesertTempleGenerator;hasPlacedChest:[Z", opcode = Opcodes.GETFIELD, args = "array=set"))
    private void redirectSetHasPlacedChest(boolean[] array, int index, boolean value) {
        this.hasPlacedChestAtomic.compareAndSet(index, false, value);
    }

    @Dynamic
    @SuppressWarnings({"InvalidInjectorMethodSignature", "RedundantSuppression"})
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/structure/DesertTempleGenerator;hasPlacedChest:[Z", opcode = Opcodes.GETFIELD, args = "array=get"))
    private boolean redirectGetHasPlacedChest(boolean[] array, int index) {
        final Boolean aBoolean = this.hasPlacedChestAtomic.get(index);
        return aBoolean != null ? aBoolean : false;
    }

}
