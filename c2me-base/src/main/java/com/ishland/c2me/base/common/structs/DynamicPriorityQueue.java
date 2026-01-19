package com.ishland.c2me.base.common.structs;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;

/**
 * A priority queue with fixed number of priorities and allows changing priorities of elements.
 * Not thread-safe.
 *
 * @param <E> the type of elements held in this collection
 */
public class DynamicPriorityQueue<E> {

    private final ObjectLinkedOpenHashSet<E>[] priorities;
    private final Object2IntMap<E> priorityMap = new Object2IntOpenHashMap<>();
    private final BitSet nonEmptyBuckets;

    private int currentMinPriority = 0;

    public DynamicPriorityQueue(int priorityCount) {
        //noinspection unchecked
        this.priorities = new ObjectLinkedOpenHashSet[priorityCount];
        this.nonEmptyBuckets = new BitSet(priorityCount);
        this.priorityMap.defaultReturnValue(-1); // Enable single-lookup optimization
        for (int i = 0; i < priorityCount; i++) {
            this.priorities[i] = new ObjectLinkedOpenHashSet<>();
        }
    }

    public void enqueue(E element, int priority) {
        if (priority < 0 || priority >= priorities.length)
            throw new IllegalArgumentException("Priority out of range");
        if (priorityMap.containsKey(element))
            throw new IllegalArgumentException("Element already in queue");

        priorities[priority].add(element);
        priorityMap.put(element, priority);
        nonEmptyBuckets.set(priority);
        if (priority < currentMinPriority)
            currentMinPriority = priority;
    }

    public void changePriority(E element, int priority) {
        if (priority < 0 || priority >= priorities.length)
            throw new IllegalArgumentException("Priority out of range");

        // Single lookup optimization: getInt returns -1 if not present
        int oldPriority = priorityMap.getInt(element);
        if (oldPriority < 0) return; // not in queue, ignored
        if (oldPriority == priority) return; // nothing to do

        priorities[oldPriority].remove(element);
        if (priorities[oldPriority].isEmpty()) {
            nonEmptyBuckets.clear(oldPriority);
        }
        priorities[priority].add(element);
        nonEmptyBuckets.set(priority);
        priorityMap.put(element, priority);

        if (priority < currentMinPriority) currentMinPriority = priority;
    }

    @Nullable
    public E dequeue() {
        // Use BitSet to jump directly to next non-empty bucket (O(1) instead of O(n))
        int nextPriority = nonEmptyBuckets.nextSetBit(currentMinPriority);
        if (nextPriority < 0) {
            currentMinPriority = priorities.length; // Mark as exhausted
            return null;
        }

        currentMinPriority = nextPriority;
        ObjectLinkedOpenHashSet<E> bucket = this.priorities[nextPriority];
        E element = bucket.removeFirst();
        priorityMap.removeInt(element);

        if (bucket.isEmpty()) {
            nonEmptyBuckets.clear(nextPriority);
        }
        return element;
    }

    public boolean contains(E element) {
        return priorityMap.containsKey(element);
    }

    public void remove(E element) {
        // Single lookup optimization: getInt returns -1 if not present
        int priority = priorityMap.getInt(element);
        if (priority < 0) return; // not in queue, ignore
        priorities[priority].remove(element);
        if (priorities[priority].isEmpty()) {
            nonEmptyBuckets.clear(priority);
        }
        priorityMap.removeInt(element);
    }

    public int size() {
        return priorityMap.size();
    }

}
