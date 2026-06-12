package com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.StructureLocator;
import net.minecraft.world.gen.structure.Structure;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

@Mixin(StructureLocator.class)
public class MixinStructureChecker {

    @Mutable
    @Shadow @Final private Long2ObjectMap<Object2IntMap<Structure>> cachedStructuresByChunkPos;

    @Mutable
    @Shadow @Final private Map<Structure, Long2BooleanMap> generationPossibilityByStructure;

    @Unique
    private final Object mapMutex = new Object();

    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void onInit(CallbackInfo info) {
        this.cachedStructuresByChunkPos = Long2ObjectMaps.synchronize(this.cachedStructuresByChunkPos);
        this.generationPossibilityByStructure = Object2ObjectMaps.synchronize(new Object2ObjectOpenHashMap<>(), this.mapMutex);
    }

    @Redirect(method = "cache(JLit/unimi/dsi/fastutil/objects/Object2IntMap;)V", at = @At(value = "INVOKE", target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V"))
    private void redirectForEach(Collection<Long2BooleanMap> instance, Consumer<Long2BooleanMap> consumer) {
        synchronized (this.mapMutex) {
            final Iterator<Long2BooleanMap> iterator = instance.iterator();
            while (iterator.hasNext()) {
                final Long2BooleanMap next = iterator.next();
                consumer.accept(next);
                if (next.isEmpty()) iterator.remove();
            }
        }
    }

    // The outer map is synchronized, but vanilla mutates the returned inner
    // Long2BooleanMap (computeIfAbsent in getStructurePresence) OUTSIDE the
    // mutex while the eviction redirect above removes from the same inner map
    // under it - a put/remove race on an open-hash table. Wrap inner maps with
    // the same mutex at their single creation site so all inner-map access
    // shares one lock.
    @SuppressWarnings("unchecked")
    @Redirect(method = "getStructurePresence", at = @At(value = "INVOKE", target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private Object redirectInnerMapCreation(Map<Object, Object> map, Object key, Function<Object, Object> factory) {
        return map.computeIfAbsent(key, k -> Long2BooleanMaps.synchronize((Long2BooleanMap) factory.apply(k), this.mapMutex));
    }

}
