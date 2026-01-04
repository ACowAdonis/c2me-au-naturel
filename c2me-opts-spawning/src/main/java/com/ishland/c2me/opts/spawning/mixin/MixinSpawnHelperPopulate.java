package com.ishland.c2me.opts.spawning.mixin;

import com.ishland.c2me.opts.spawning.common.SpawnBlacklist;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin to skip entity instantiation for blacklisted mobs during chunk generation spawning.
 * This prevents threading issues caused by entity constructors running on worldgen threads.
 */
@Mixin(SpawnHelper.class)
public class MixinSpawnHelperPopulate {

    /**
     * Redirect the EntityType.create call in populateEntities to skip blacklisted entities.
     * This is called during chunk generation spawning (SpawnReason.CHUNK_GENERATION).
     */
    @Redirect(
            method = "populateEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityType;create(Lnet/minecraft/world/World;)Lnet/minecraft/entity/Entity;")
    )
    private static Entity c2me$skipBlacklistedMobsInPopulate(EntityType<?> type, World world) {
        if (SpawnBlacklist.isBlacklisted(type)) {
            return null;
        }
        return type.create(world);
    }

}
