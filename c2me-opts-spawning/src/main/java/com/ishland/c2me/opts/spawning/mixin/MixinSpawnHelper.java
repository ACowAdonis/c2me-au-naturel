package com.ishland.c2me.opts.spawning.mixin;

import com.ishland.c2me.opts.spawning.common.SpawnBlacklist;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.SpawnHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to skip entity instantiation for blacklisted mobs during natural spawning.
 * This prevents threading issues caused by entity constructors running on worldgen threads.
 */
@Mixin(SpawnHelper.class)
public class MixinSpawnHelper {

    /**
     * Inject at the head of createMob to skip instantiation for blacklisted entities.
     * This is called during natural mob spawning (SpawnReason.NATURAL).
     */
    @Inject(method = "createMob", at = @At("HEAD"), cancellable = true)
    private static void c2me$skipBlacklistedMobs(ServerWorld world, EntityType<?> type, CallbackInfoReturnable<MobEntity> cir) {
        if (SpawnBlacklist.isBlacklisted(type)) {
            cir.setReturnValue(null);
        }
    }

}
