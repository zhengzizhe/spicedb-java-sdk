package com.authx.sdk.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SdkMetricsTest {

    @Test
    void latencyPercentiles_basicRecording() {
        SdkMetrics metrics = new SdkMetrics();
        for (int i = 0; i < 100; i++) {
            metrics.recordRequest(1000, false);
        }
        SdkMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.latencyP50Ms()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(snapshot.latencyP99Ms()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void latencyRecording_aboveMaxTrackable_clampsWithoutException() {
        // SR:C9 — cap raised from 60s to 600s. A sample above the ceiling is
        // clamped AND counted in latencyOverflowCount so it stays observable.
        SdkMetrics metrics = new SdkMetrics();
        metrics.recordRequest(700_000_000L, false);  // 700s > 600s cap
        SdkMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.latencyP50Ms()).isCloseTo(600_000.0, org.assertj.core.data.Offset.offset(1000.0));
        assertThat(snapshot.latencyOverflowCount()).isEqualTo(1L);
    }

    @Test
    void circuitBreakerState_defaultIsNA() {
        SdkMetrics metrics = new SdkMetrics();
        assertThat(metrics.circuitBreakerState()).isEqualTo("N/A");
    }

    @Test
    void circuitBreakerState_usesSupplier() {
        SdkMetrics metrics = new SdkMetrics();
        metrics.setCircuitBreakerStateSupplier(() -> "OPEN");
        assertThat(metrics.circuitBreakerState()).isEqualTo("OPEN");
    }

}
