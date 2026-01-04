package com.ishland.c2me.opts.allocs.common;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.BitSet;
import java.util.function.IntFunction;

public class ObjectCachingUtils {

    private static final IntFunction<BitSet> bitSetConstructor = BitSet::new;
    // C2ME fix: Limit cache size to prevent unbounded growth
    private static final int MAX_BITSET_CACHE_SIZE = 64;

    // C2ME fix: Use LinkedHashMap to enable LRU eviction
    public static ThreadLocal<Int2ObjectLinkedOpenHashMap<BitSet>> BITSETS = ThreadLocal.withInitial(Int2ObjectLinkedOpenHashMap::new);

    private ObjectCachingUtils() {
    }

    public static BitSet getCachedOrNewBitSet(int bits) {
        final Int2ObjectLinkedOpenHashMap<BitSet> cache = BITSETS.get();

        // C2ME fix: Evict BEFORE adding to maintain strict size bound
        // Use while loop to handle edge cases where multiple evictions are needed
        while (cache.size() >= MAX_BITSET_CACHE_SIZE && !cache.containsKey(bits)) {
            cache.removeFirst();
        }

        final BitSet bitSet = cache.computeIfAbsent(bits, bitSetConstructor);
        bitSet.clear();
        return bitSet;
    }

}
