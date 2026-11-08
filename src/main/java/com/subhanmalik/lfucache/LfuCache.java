package com.subhanmalik.lfucache;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded, thread-safe Least Frequently Used (LFU) cache.
 *
 * <p>Both {@link #get(Object)} and {@link #put(Object, Object)} run in
 * amortised O(1) time. The trick is the classic frequency-bucket design:
 * entries live in doubly-linked buckets keyed by access count, so the
 * eviction victim — the least recently used entry among the least
 * frequently used — is always the first element of the lowest bucket.
 *
 * <p>Eviction rule: when the cache is full, evict the entry with the
 * lowest access frequency; ties are broken by recency (the entry in that
 * bucket that was touched longest ago goes first). A brand-new entry
 * starts at frequency 1, so one-hit wonders are the first to leave —
 * this is exactly why LFU keeps hot keys alive where LRU would churn.
 *
 * <p>Null keys and null values are rejected. All public methods are safe
 * to call from multiple threads.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class LfuCache<K, V> {

    /** One cached entry. Identity is the key alone, so a node can be
     *  removed from its frequency bucket by key. */
    private static final class Node<K, V> {
        final K key;
        V value;
        int frequency = 1;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Node<?, ?> other)) return false;
            return Objects.equals(key, other.key);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(key);
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> entries;
    /** frequency -> entries at that frequency, in last-touch order. */
    private final Map<Integer, LinkedHashSet<Node<K, V>>> buckets;
    /** Lowest frequency currently present; the eviction bucket. */
    private int minFrequency = 1;

    private final ReentrantLock lock = new ReentrantLock();
    private long hits;
    private long misses;
    private long evictions;

    /**
     * @param capacity maximum number of entries; must be positive
     * @throws IllegalArgumentException if capacity is not positive
     */
    public LfuCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
        this.entries = new HashMap<>(capacity);
        this.buckets = new HashMap<>();
    }

    /**
     * Returns the value for {@code key}, or {@code null} if absent.
     * A hit promotes the entry to the next frequency bucket.
     */
    public V get(K key) {
        Objects.requireNonNull(key, "key must not be null");
        lock.lock();
        try {
            Node<K, V> node = entries.get(key);
            if (node == null) {
                misses++;
                return null;
            }
            hits++;
            bumpFrequency(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stores {@code key -> value}. Updating an existing key replaces its
     * value and counts as an access (frequency +1). Inserting into a full
     * cache evicts the LFU entry first.
     */
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        lock.lock();
        try {
            Node<K, V> existing = entries.get(key);
            if (existing != null) {
                existing.value = value;
                bumpFrequency(existing);
                return;
            }
            if (entries.size() == capacity) {
                evictOne();
            }
            Node<K, V> node = new Node<>(key, value);
            entries.put(key, node);
            buckets.computeIfAbsent(1, f -> new LinkedHashSet<>()).add(node);
            minFrequency = 1;
        } finally {
            lock.unlock();
        }
    }

    /** Removes {@code key} if present. Returns true if something was removed. */
    public boolean remove(K key) {
        Objects.requireNonNull(key, "key must not be null");
        lock.lock();
        try {
            Node<K, V> node = entries.remove(key);
            if (node == null) {
                return false;
            }
            LinkedHashSet<Node<K, V>> bucket = buckets.get(node.frequency);
            bucket.remove(node);
            if (bucket.isEmpty()) {
                buckets.remove(node.frequency);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean containsKey(K key) {
        Objects.requireNonNull(key, "key must not be null");
        lock.lock();
        try {
            return entries.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    /** Current number of entries. */
    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Access frequency of {@code key}, or 0 if the key is not cached.
     * A freshly inserted key has frequency 1.
     */
    public int frequencyOf(K key) {
        Objects.requireNonNull(key, "key must not be null");
        lock.lock();
        try {
            Node<K, V> node = entries.get(key);
            return node == null ? 0 : node.frequency;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Snapshot of cached keys grouped by frequency, lowest first.
     * Within a frequency, keys appear in last-touch order.
     */
    public Map<Integer, java.util.List<K>> keysByFrequency() {
        lock.lock();
        try {
            Map<Integer, java.util.List<K>> snapshot = new java.util.TreeMap<>();
            for (Map.Entry<Integer, LinkedHashSet<Node<K, V>>> e : buckets.entrySet()) {
                java.util.List<K> keys = new java.util.ArrayList<>(e.getValue().size());
                for (Node<K, V> node : e.getValue()) {
                    keys.add(node.key);
                }
                snapshot.put(e.getKey(), keys);
            }
            return snapshot;
        } finally {
            lock.unlock();
        }
    }

    /** Removes all entries and resets the statistics counters. */
    public void clear() {
        lock.lock();
        try {
            entries.clear();
            buckets.clear();
            minFrequency = 1;
            hits = 0;
            misses = 0;
            evictions = 0;
        } finally {
            lock.unlock();
        }
    }

    /** Point-in-time statistics snapshot. */
    public CacheStats stats() {
        lock.lock();
        try {
            return new CacheStats(hits, misses, evictions, entries.size(), capacity);
        } finally {
            lock.unlock();
        }
    }

    /** Moves a node one frequency bucket up, maintaining minFrequency. */
    private void bumpFrequency(Node<K, V> node) {
        LinkedHashSet<Node<K, V>> oldBucket = buckets.get(node.frequency);
        oldBucket.remove(node);
        if (oldBucket.isEmpty()) {
            buckets.remove(node.frequency);
            if (node.frequency == minFrequency) {
                minFrequency++;
            }
        }
        node.frequency++;
        buckets.computeIfAbsent(node.frequency, f -> new LinkedHashSet<>()).add(node);
    }

    /** Evicts the least recently used entry of the lowest frequency bucket. */
    private void evictOne() {
        LinkedHashSet<Node<K, V>> bucket = buckets.get(minFrequency);
        Node<K, V> victim = bucket.iterator().next(); // oldest in the bucket
        bucket.remove(victim);
        if (bucket.isEmpty()) {
            buckets.remove(minFrequency);
        }
        entries.remove(victim.key);
        evictions++;
    }
}
