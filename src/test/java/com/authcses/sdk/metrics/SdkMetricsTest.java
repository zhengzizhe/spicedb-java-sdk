package com.authcses.sdk.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SdkMetricsTest {

    @Test
    void latencyPercentiles_basicRecording() {
        var metrics = new SdkMetrics();
        for (int i = 0; i < 100; i++) {
            metrics.recordRequest(1000, false);
        }
        var snapshot = metrics.snapshot();
        assertThat(snapshot.latencyP50Ms()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(snapshot.latencyP99Ms()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void latencyRecording_aboveMaxTrackable_clampsWithoutException() {
        var metrics = new SdkMetrics();
        metrics.recordRequest(120_000_000L, false);
        var snapshot = metrics.snapshot();
        assertThat(snapshot.latencyP50Ms()).isCloseTo(60_000.0, org.assertj.core.data.Offset.offset(100.0));
    }

    @Test
    void circuitBreakerState_defaultIsNA() {
        var metrics = new SdkMetrics();
        assertThat(metrics.circuitBreakerState()).isEqualTo("N/A");
    }

    @Test
    void circuitBreakerState_usesSupplier() {
        var metrics = new SdkMetrics();
        metrics.setCircuitBreakerStateSupplier(() -> "OPEN");
        assertThat(metrics.circuitBreakerState()).isEqualTo("OPEN");
    }

    @Test
    void cacheHitRate_zeroDivision() {
        var metrics = new SdkMetrics();
        assertThat(metrics.cacheHitRate()).isEqualTo(0.0);
    }

    @Test
    void cacheHitRate_computed() {
        var metrics = new SdkMetrics();
        for (int i = 0; i < 80; i++) metrics.recordCacheHit();
        for (int i = 0; i < 20; i++) metrics.recordCacheMiss();
        assertThat(metrics.cacheHitRate()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.01));
    }
}
