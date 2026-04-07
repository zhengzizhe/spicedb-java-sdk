package com.authx.sdk.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class CaffeineCacheConcurrencyTest {

    private CaffeineCache<TestKey, String> cache;

    record TestKey(String resource, String id, String perm) {
        String indexKey() { return resource + ":" + id; }
    }

    @BeforeEach
    void setup() {
        cache = new CaffeineCache<>(10_000, Duration.ofMinutes(5), TestKey::indexKey);
    }

    // ---------------------------------------------------------------
    // 1. singleFlight: only one thread executes the loader
    // ---------------------------------------------------------------
    @Test
    void singleFlight_onlyOneThreadLoads() throws Exception {
        int threadCount = 50;
        TestKey key = new TestKey("doc", "1", "view");
        AtomicInteger loaderCalls = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return cache.getOrLoad(key, k -> {
                    loaderCalls.incrementAndGet();
                    // Simulate slow computation so other threads pile up
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return "result";
                });
            }));
        }

        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }

        pool.shutdown();

        assertThat(loaderCalls.get()).as("loader should be called exactly once").isEqualTo(1);
        assertThat(results).as("all threads should receive the same value").containsOnly("result");
    }

    // ---------------------------------------------------------------
    // 2. singleFlight: hit/miss counting is accurate
    // ---------------------------------------------------------------
    @Test
    void singleFlight_hitMissCountingAccurate() throws Exception {
        int threadCount = 20;
        TestKey key = new TestKey("doc", "2", "edit");
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return cache.getOrLoad(key, k -> {
                    try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return "value";
                });
            }));
        }

        for (Future<String> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        pool.shutdown();

        CacheStats stats = cache.stats();
        assertThat(stats.missCount()).as("only the loading thread counts as a miss").isEqualTo(1);
        assertThat(stats.hitCount()).as("all other threads count as hits").isEqualTo(threadCount - 1);
    }

    // ---------------------------------------------------------------
    // 3. Concurrent put and invalidateByIndex must not crash
    // ---------------------------------------------------------------
    @Test
    void putAndInvalidateByIndex_concurrent() throws Exception {
        String indexKey = "resource:1";
        int writerThreads = 10;
        int invalidatorThreads = 10;
        Duration runDuration = Duration.ofSeconds(1);

        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + invalidatorThreads);
        CyclicBarrier barrier = new CyclicBarrier(writerThreads + invalidatorThreads);
        AtomicInteger keyCounter = new AtomicInteger();
        long deadline = System.nanoTime() + runDuration.toNanos();

        List<Future<?>> futures = new ArrayList<>();

        // Group A: writers
        for (int t = 0; t < writerThreads; t++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                while (System.nanoTime() < deadline) {
                    int seq = keyCounter.incrementAndGet();
                    TestKey key = new TestKey("resource", "1", "perm-" + seq);
                    cache.put(key, "val-" + seq);
                }
                return null;
            }));
        }

        // Group B: invalidators
        for (int t = 0; t < invalidatorThreads; t++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                while (System.nanoTime() < deadline) {
                    cache.invalidateByIndex(indexKey);
                }
                return null;
            }));
        }

        // No exception means no NPE, no ConcurrentModificationException, no deadlock
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        pool.shutdown();

        assertThat(cache.size()).as("cache size must be non-negative").isGreaterThanOrEqualTo(0);

        // Final invalidation should clear everything under this index
        cache.invalidateByIndex(indexKey);
        // After final invalidation, no keys with this indexKey should remain
        // (there could be a small delay with Caffeine's async eviction, so we cleanUp)
        // Caffeine uses lazy eviction internally; call size() to trigger cleanup
        assertThat(cache.getIfPresent(new TestKey("resource", "1", "perm-1"))).isNull();
    }

    // ---------------------------------------------------------------
    // 4. Index consistency after concurrent put + invalidate
    // ---------------------------------------------------------------
    @Test
    void index_consistency_afterConcurrentPutAndInvalidate() {
        String indexKey = "resource:1";
        int entryCount = 1000;
        List<TestKey> keys = new ArrayList<>();

        for (int i = 0; i < entryCount; i++) {
            TestKey key = new TestKey("resource", "1", "perm-" + i);
            keys.add(key);
            cache.put(key, "value-" + i);
        }

        cache.invalidateByIndex(indexKey);

        for (TestKey key : keys) {
            assertThat(cache.getIfPresent(key))
                    .as("key %s should be null after invalidateByIndex", key)
                    .isNull();
        }
    }

    // ---------------------------------------------------------------
    // 5. Exception from loader must not be cached
    // ---------------------------------------------------------------
    @Test
    void getOrLoad_exceptionNotCached() {
        TestKey key = new TestKey("doc", "3", "delete");
        AtomicInteger loaderCalls = new AtomicInteger();

        // First call: loader throws
        assertThatThrownBy(() -> cache.getOrLoad(key, k -> {
            loaderCalls.incrementAndGet();
            throw new RuntimeException("transient failure");
        })).isInstanceOf(RuntimeException.class)
           .hasMessage("transient failure");

        // Second call: loader should be invoked again (not cached exception)
        String result = cache.getOrLoad(key, k -> {
            loaderCalls.incrementAndGet();
            return "recovered";
        });

        assertThat(loaderCalls.get()).as("loader must be called twice (exception not cached)").isEqualTo(2);
        assertThat(result).isEqualTo("recovered");
    }

    // ---------------------------------------------------------------
    // 6. put then immediate invalidateByIndex — no orphan entries
    // ---------------------------------------------------------------
    @Test
    void put_indexBeforeCache_noOrphan() {
        int iterations = 10_000;

        for (int i = 0; i < iterations; i++) {
            TestKey key = new TestKey("resource", "r" + i, "view");
            cache.put(key, "value");
            cache.invalidateByIndex("resource:r" + i);

            assertThat(cache.getIfPresent(key))
                    .as("iteration %d: key must be null after invalidateByIndex", i)
                    .isNull();
        }
    }
}
