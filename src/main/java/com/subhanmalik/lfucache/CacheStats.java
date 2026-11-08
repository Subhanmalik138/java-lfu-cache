package com.subhanmalik.lfucache;

/**
 * Immutable snapshot of a cache's usage counters.
 *
 * @param hits      successful {@code get} calls
 * @param misses    {@code get} calls for absent keys
 * @param evictions entries removed to make room for new inserts
 * @param size      entries currently cached
 * @param capacity  maximum entries the cache holds
 */
public record CacheStats(long hits, long misses, long evictions, int size, int capacity) {

    /** Fraction of gets that hit, in [0, 1]; 0 when no gets happened yet. */
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    @Override
    public String toString() {
        return "size=%d/%d hits=%d misses=%d evictions=%d hitRate=%.1f%%"
                .formatted(size, capacity, hits, misses, evictions, hitRate() * 100);
    }
}
