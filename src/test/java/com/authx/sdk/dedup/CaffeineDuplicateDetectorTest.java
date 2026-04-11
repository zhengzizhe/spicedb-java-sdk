package com.authx.sdk.dedup;

import com.authx.sdk.spi.DuplicateDetector;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link CaffeineDuplicateDetector} and the {@link DuplicateDetector}
 * SPI contract.
 */
class CaffeineDuplicateDetectorTest {

    // ─── Core semantics ─────────────────────────────────────────────────

    @Test
    void firstCall_returnsTrue() {
        var det = new CaffeineDuplicateDetector<String>(100, Duration.ofMinutes(1));
        assertThat(det.tryProcess("a")).isTrue();
    }

    @Test
    void secondCall_withSameKey_returnsFalse() {
        var det = new CaffeineDuplicateDetector<String>(100, Duration.ofMinutes(1));
        det.tryProcess("a");
        assertThat(det.tryProcess("a")).isFalse();
    }

    @Test
    void differentKeys_bothReturnTrue() {
        var det = new CaffeineDuplicateDetector<String>(100, Duration.ofMinutes(1));
        assertThat(det.tryProcess("a")).isTrue();
        assertThat(det.tryProcess("b")).isTrue();
    }

    @Test
    void nullKey_failsOpen_returnsTrue() {
        var det = new CaffeineDuplicateDetector<String>(100, Duration.ofMinutes(1));
        // Null keys get pass-through — fail-open, not drop.
        assertThat(det.tryProcess(null)).isTrue();
        assertThat(det.tryProcess(null)).isTrue();  // still true, never deduped
    }

    @Test
    void size_tracksDistinctKeys() {
        var det = new CaffeineDuplicateDetector<String>(100, Duration.ofMinutes(1));
        det.tryProcess("a");
        det.tryProcess("b");
        det.tryProcess("a");  // duplicate, not counted
        // Caffeine's estimatedSize is eventually-consistent; give it a tick.
        assertThat(det.size()).isBetween(1L, 2L);
    }

    @Test
    void clear_allowsReprocessing() {
        var det = new CaffeineDuplicateDetector<String>(100, Duration.ofMinutes(1));
        det.tryProcess("a");
        assertThat(det.tryProcess("a")).isFalse();
        det.clear();
        assertThat(det.tryProcess("a")).isTrue();
    }

    // ─── Construction validation ────────────────────────────────────────

    @Test
    void nonPositiveMaxEntries_rejected() {
        assertThatThrownBy(() -> new CaffeineDuplicateDetector<String>(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxEntries");
        assertThatThrownBy(() -> new CaffeineDuplicateDetector<String>(-1, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveTtl_rejected() {
        assertThatThrownBy(() -> new CaffeineDuplicateDetector<String>(100, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
        assertThatThrownBy(() -> new CaffeineDuplicateDetector<String>(100, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── Generic type flexibility ───────────────────────────────────────

    @Test
    void worksWithNonStringKeys() {
        var det = new CaffeineDuplicateDetector<Long>(100, Duration.ofMinutes(1));
        assertThat(det.tryProcess(42L)).isTrue();
        assertThat(det.tryProcess(42L)).isFalse();
        assertThat(det.tryProcess(43L)).isTrue();
    }

    // ─── Concurrency ────────────────────────────────────────────────────

    @Test
    void concurrentCallsWithSameKey_exactlyOneReturnsTrue() throws Exception {
        var det = new CaffeineDuplicateDetector<String>(100, Duration.ofMinutes(1));
        int threads = 50;
        var barrier = new CyclicBarrier(threads);
        var trueCount = new AtomicInteger(0);
        var pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    barrier.await();  // all fire simultaneously
                    if (det.tryProcess("hot-key")) {
                        trueCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Exactly ONE of the 50 concurrent callers must have won the claim.
        assertThat(trueCount.get()).isEqualTo(1);
    }

    // ─── SPI factory ────────────────────────────────────────────────────

    @Test
    void noopFactory_alwaysReturnsTrue() {
        DuplicateDetector<String> noop = DuplicateDetector.noop();
        assertThat(noop.tryProcess("a")).isTrue();
        assertThat(noop.tryProcess("a")).isTrue();  // never dedupes
        assertThat(noop.tryProcess("a")).isTrue();
    }

    @Test
    void lruFactory_returnsCaffeineBackedInstance() {
        DuplicateDetector<String> det = DuplicateDetector.lru(100, Duration.ofMinutes(1));
        assertThat(det.tryProcess("a")).isTrue();
        assertThat(det.tryProcess("a")).isFalse();
    }

    // ─── TTL expiry (slow — explicit sleep) ────────────────────────────

    @Test
    void expiredKeys_allowReprocessing() throws InterruptedException {
        var det = new CaffeineDuplicateDetector<String>(100, Duration.ofMillis(100));
        det.tryProcess("a");
        assertThat(det.tryProcess("a")).isFalse();
        // Wait past TTL; Caffeine expires lazily but should evict by the next access.
        Thread.sleep(200);
        // May need to poke Caffeine; the next tryProcess both accesses and potentially evicts.
        // Worst case: hit the cleanup by writing a different key first.
        det.tryProcess("b");
        Thread.sleep(50);
        assertThat(det.tryProcess("a")).isTrue();
    }
}
