package com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import net.minecraft.state.property.IntProperty;
import net.minecraft.world.gen.stateprovider.RandomizedIntBlockStateProvider;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RandomizedIntBlockStateProvider.class)
public class MixinRandomizedIntBlockStateProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("C2ME/RandomizedIntBlockStateProvider");

    @Shadow @Nullable private IntProperty property;

    @Redirect(method = "get", at = @At(value = "FIELD", target = "Lnet/minecraft/world/gen/stateprovider/RandomizedIntBlockStateProvider;property:Lnet/minecraft/state/property/IntProperty;", opcode = Opcodes.PUTFIELD))
    private void redirectGetProperty(RandomizedIntBlockStateProvider randomizedIntBlockStateProvider, IntProperty value) {
        if (this.property != null) LOGGER.warn("Detected different property settings in RandomizedIntBlockStateProvider! Expected {} but got {}", this.property, value);
        synchronized (this) {
            this.property = value;
        }
    }

}
