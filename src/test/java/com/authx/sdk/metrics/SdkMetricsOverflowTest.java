package com.authx.sdk.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SR:C9 — latency values exceeding the histogram trackable range must be
 * counted in a dedicated overflow counter, not hidden by silent clamp.
 */
class SdkMetricsOverflowTest {

    /** Matches {@code SdkMetrics.MAX_TRACKABLE_MICROS}; kept in sync deliberately. */
    private static final long MAX_TRACKABLE_MICROS = 600_000_000L;

    @Test
    void sample_above_ceiling_increments_overflow_counter() {
        com.authx.sdk.metrics.SdkMetrics m = new SdkMetrics();

        m.recordRequest(1_000, false);   // 1 ms — in range
        m.recordRequest(1_000, false);
        m.recordRequest(1_000, false);
        m.recordRequest(MAX_TRACKABLE_MICROS + 1, false);  // 10min + 1µs — clamps

        assertEquals(1, m.latencyOverflowCount(),
                "exactly one sample exceeded the clamp");
        com.authx.sdk.metrics.SdkMetrics.Snapshot snap = m.snapshot();
        assertEquals(1, snap.latencyOverflowCount());
        // p99 is NOT dominated by the 10-minute sample because we only have
        // 4 samples total; p99 of {1ms, 1ms, 1ms, clamp} is the clamp. But
        // p50 must still be 1ms, proving the three in-range samples remain
        // visible in the distribution.
        assertEquals(1.0, snap.latencyP50Ms(), 0.1);
    }

    @Test
    void toString_shows_overflow_when_nonzero_hides_when_zero() {
        com.authx.sdk.metrics.SdkMetrics m1 = new SdkMetrics();
        m1.recordRequest(1_000, false);
        assertFalse(m1.snapshot().toString().contains("overflow="),
                "healthy window should not mention overflow");

        com.authx.sdk.metrics.SdkMetrics m2 = new SdkMetrics();
        m2.recordRequest(MAX_TRACKABLE_MICROS + 1, false);
        assertTrue(m2.snapshot().toString().contains("overflow=1"),
                "overflowed window should print overflow=N");
    }

    @Test
    void in_range_sample_does_not_increment_overflow() {
        com.authx.sdk.metrics.SdkMetrics m = new SdkMetrics();
        m.recordRequest(MAX_TRACKABLE_MICROS, false);  // exactly at ceiling
        assertEquals(0, m.latencyOverflowCount(),
                "ceiling value itself is in-range; only > ceiling counts");
    }
}
