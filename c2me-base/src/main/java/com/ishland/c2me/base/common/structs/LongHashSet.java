package com.ishland.c2me.base.common.structs;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * C2ME performance fix: This class now extends LongOpenHashSet directly instead of
 * wrapping HashSet<Long>. The previous implementation had severe performance issues:
 * 1. Boxing overhead: Every long was boxed to Long
 * 2. Tree node lookups: HashMap uses tree nodes for collision resolution, causing
 *    O(log n) lookups instead of O(1)
 * 3. comparableClassFor() overhead: Expensive reflection calls during tree traversal
 *
 * Spark profiling showed LongHashSet.contains() taking 1.09% of tick time, with
 * HashMap$TreeNode.find() at 1.01% - this fix should eliminate that overhead entirely.
 */
public class LongHashSet extends LongOpenHashSet {

    public LongHashSet() {
        super();
    }

    public LongHashSet(int expected) {
        super(expected);
    }

    public LongHashSet(int expected, float f) {
        super(expected, f);
    }
}
