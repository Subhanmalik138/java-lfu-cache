package com.subhanmalik.lfucache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class LfuCacheTest {

    @Test
    void getReturnsNullForMissingKey() {
        LfuCache<String, Integer> cache = new LfuCache<>(3);
        assertNull(cache.get("nope"));
        assertEquals(1, cache.stats().misses());
    }

    @Test
    void putAndGetRoundTrip() {
        LfuCache<String, Integer> cache = new LfuCache<>(3);
        cache.put("a", 1);
        assertEquals(1, cache.get("a"));
        assertEquals(1, cache.size());
    }

    @Test
    void putUpdatesValueWithoutGrowingSize() {
        LfuCache<String, Integer> cache = new LfuCache<>(3);
        cache.put("a", 1);
        cache.put("a", 2);
        assertEquals(2, cache.get("a"));
        assertEquals(1, cache.size());
    }

    @Test
    void evictsLeastFrequentlyUsed() {
        LfuCache<String, String> cache = new LfuCache<>(3);
        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");
        cache.get("a");
        cache.get("a"); // a: freq 3
        cache.get("b"); // b: freq 2, c: freq 1
        cache.put("d", "D"); // must evict c
        assertFalse(cache.containsKey("c"));
        assertTrue(cache.containsKey("a"));
        assertTrue(cache.containsKey("b"));
        assertTrue(cache.containsKey("d"));
        assertEquals(1, cache.stats().evictions());
    }

    @Test
    void tieBreakIsLeastRecentlyUsedWithinLowestFrequency() {
        LfuCache<String, String> cache = new LfuCache<>(3);
        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");
        cache.get("a"); // a: freq 2; b and c still freq 1, b older
        cache.put("d", "D"); // evicts b, the oldest of the freq-1 entries
        assertFalse(cache.containsKey("b"));
        assertTrue(cache.containsKey("c"));
    }

    @Test
    void hotKeySurvivesRepeatedEvictions() {
        LfuCache<String, String> cache = new LfuCache<>(2);
        cache.put("hot", "H");
        cache.put("cold", "C");
        for (int i = 0; i < 5; i++) {
            cache.get("hot");
        }
        cache.put("x", "X"); // evicts cold
        cache.put("y", "Y"); // evicts x, not hot
        assertTrue(cache.containsKey("hot"));
        assertEquals(2, cache.stats().evictions());
    }

    @Test
    void frequencyOfTracksAccesses() {
        LfuCache<String, String> cache = new LfuCache<>(3);
        cache.put("a", "A");
        assertEquals(1, cache.frequencyOf("a"));
        cache.get("a");
        cache.get("a");
        assertEquals(3, cache.frequencyOf("a"));
        assertEquals(0, cache.frequencyOf("missing"));
    }

    @Test
    void removeDeletesEntryAndFreesCapacity() {
        LfuCache<String, String> cache = new LfuCache<>(2);
        cache.put("a", "A");
        cache.put("b", "B");
        assertTrue(cache.remove("a"));
        assertFalse(cache.remove("a"));
        cache.put("c", "C"); // no eviction needed
        assertEquals(0, cache.stats().evictions());
        assertEquals(2, cache.size());
    }

    @Test
    void clearEmptiesCacheAndResetsStats() {
        LfuCache<String, String> cache = new LfuCache<>(2);
        cache.put("a", "A");
        cache.get("a");
        cache.get("missing");
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.stats().hits());
        assertEquals(0, cache.stats().misses());
        assertEquals(0, cache.frequencyOf("a"));
    }

    @Test
    void capacityOneKeepsOnlyLatest() {
        LfuCache<String, String> cache = new LfuCache<>(1);
        cache.put("a", "A");
        cache.put("b", "B");
        assertFalse(cache.containsKey("a"));
        assertEquals("B", cache.get("b"));
    }

    @Test
    void zeroOrNegativeCapacityRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LfuCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new LfuCache<>(-5));
    }

    @Test
    void nullKeyOrValueRejected() {
        LfuCache<String, String> cache = new LfuCache<>(3);
        assertThrows(NullPointerException.class, () -> cache.put(null, "v"));
        assertThrows(NullPointerException.class, () -> cache.put("k", null));
        assertThrows(NullPointerException.class, () -> cache.get(null));
    }

    @Test
    void statsHitRateComputation() {
        LfuCache<String, String> cache = new LfuCache<>(3);
        cache.put("a", "A");
        cache.get("a");
        cache.get("a");
        cache.get("missing");
        CacheStats stats = cache.stats();
        assertEquals(2, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(2.0 / 3.0, stats.hitRate(), 1e-9);
    }

    @Test
    void evictionCountTrackedAcrossManyPuts() {
        LfuCache<Integer, Integer> cache = new LfuCache<>(3);
        for (int i = 0; i < 10; i++) {
            cache.put(i, i);
        }
        assertEquals(7, cache.stats().evictions());
        assertEquals(3, cache.size());
    }

    @Test
    void minFrequencyRecoversAfterEvictingLastOfItsBucket() {
        LfuCache<String, String> cache = new LfuCache<>(2);
        cache.put("a", "A");
        cache.put("b", "B");
        cache.get("a");
        cache.get("a"); // a: freq 3, b: freq 1
        cache.put("c", "C"); // evicts b, the only freq-1 entry
        cache.get("c"); // c: freq 2; min frequency is now 2
        cache.put("d", "D"); // must evict c (freq 2), not a (freq 3)
        assertFalse(cache.containsKey("c"));
        assertTrue(cache.containsKey("a"));
    }

    @Test
    void keysByFrequencySnapshotIsGroupedLowestFirst() {
        LfuCache<String, String> cache = new LfuCache<>(4);
        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");
        cache.get("b");
        cache.get("b"); // b: freq 3; a, c: freq 1
        Map<Integer, List<String>> snapshot = cache.keysByFrequency();
        assertEquals(List.of("a", "c"), snapshot.get(1));
        assertEquals(List.of("b"), snapshot.get(3));
        assertFalse(snapshot.containsKey(2));
    }

    @Test
    void concurrentAccessKeepsSizeInvariant() throws InterruptedException {
        LfuCache<Integer, Integer> cache = new LfuCache<>(50);
        int threads = 8;
        int opsPerThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int base = t * opsPerThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        cache.put(base + i, i);
                        cache.get(base + (i % 100));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(cache.size() <= 50);
        assertEquals(opsPerThread * threads - cache.size(), cache.stats().evictions());
    }
}
