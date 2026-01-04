package com.ishland.c2me.opts.spawning.common;

import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

public class SpawnBlacklist {

    /**
     * Check if the given entity type is blacklisted from natural worldgen spawning.
     *
     * @param entityType the entity type to check
     * @return true if the entity should be skipped during worldgen spawning
     */
    public static boolean isBlacklisted(EntityType<?> entityType) {
        if (Config.spawnBlacklistSet.isEmpty()) {
            return false;
        }
        Identifier id = Registries.ENTITY_TYPE.getId(entityType);
        return Config.spawnBlacklistSet.contains(id.toString());
    }

}
