package com.authx.sdk.spi;

import com.authx.sdk.spi.HealthProbe.CombineMode;
import com.authx.sdk.spi.HealthProbe.ProbeResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link HealthProbe} interface — focuses on the combinator semantics
 * ({@code all}, {@code any}) and {@link ProbeResult} normalization.
 */
class HealthProbeTest {

    // ─── ProbeResult ─────────────────────────────────────────────────────

    @Test
    void probeResult_up_leaf_hasEmptyChildren() {
        com.authx.sdk.spi.HealthProbe.ProbeResult r = ProbeResult.up("foo", Duration.ofMillis(5), "ok");
        assertThat(r.healthy()).isTrue();
        assertThat(r.latencyMs()).isEqualTo(5);
        assertThat(r.details()).isEqualTo("ok");
        assertThat(r.children()).isEmpty();
    }

    @Test
    void probeResult_down_leaf_hasEmptyChildren() {
        com.authx.sdk.spi.HealthProbe.ProbeResult r = ProbeResult.down("foo", Duration.ofMillis(99), "boom");
        assertThat(r.healthy()).isFalse();
        assertThat(r.latencyMs()).isEqualTo(99);
        assertThat(r.details()).isEqualTo("boom");
    }

    @Test
    void probeResult_nullChildren_normalizedToEmptyImmutable() {
        com.authx.sdk.spi.HealthProbe.ProbeResult r = new ProbeResult("x", true, 1, "ok", null);
        assertThat(r.children()).isEmpty();
        assertThatThrownBy(() -> r.children().add(ProbeResult.up("y", Duration.ZERO, "z")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void probeResult_subMillisecondLatency_preservedInNanos() {
        // Regression: previously latency was stored as long ms, so a 500-microsecond
        // probe came back as latencyMs=0 with no way to recover the real timing.
        // After R2-13 the canonical storage is nanos and ms is derived.
        com.authx.sdk.spi.HealthProbe.ProbeResult r = ProbeResult.up("fast", Duration.ofNanos(500_000), "ok");  // 500 µs
        assertThat(r.latencyNanos()).isEqualTo(500_000L);
        assertThat(r.latencyMs()).isZero();  // backward-compat: still truncates
        assertThat(r.latency()).isEqualTo(Duration.ofNanos(500_000));
    }

    @Test
    void probeResult_children_defensivelyCopied() {
        java.util.ArrayList<com.authx.sdk.spi.HealthProbe.ProbeResult> mutable = new java.util.ArrayList<ProbeResult>();
        mutable.add(ProbeResult.up("a", Duration.ZERO, "ok"));
        com.authx.sdk.spi.HealthProbe.ProbeResult r = new ProbeResult("root", true, 0, "", mutable);
        mutable.clear();
        assertThat(r.children()).hasSize(1);
    }

    // ─── Composite: ALL (strict AND) ─────────────────────────────────────

    @Test
    void compositeAll_allUp_reportsUp() {
        com.authx.sdk.spi.HealthProbe composite = HealthProbe.all(
                () -> ProbeResult.up("p1", Duration.ofMillis(1), "ok"),
                () -> ProbeResult.up("p2", Duration.ofMillis(2), "ok"));
        com.authx.sdk.spi.HealthProbe.ProbeResult r = composite.check();
        assertThat(r.healthy()).isTrue();
        assertThat(r.children()).hasSize(2);
        assertThat(r.children()).allMatch(ProbeResult::healthy);
    }

    @Test
    void compositeAll_oneDown_reportsDown() {
        com.authx.sdk.spi.HealthProbe composite = HealthProbe.all(
                () -> ProbeResult.up("p1", Duration.ofMillis(1), "ok"),
                () -> ProbeResult.down("p2", Duration.ofMillis(2), "fail"));
        com.authx.sdk.spi.HealthProbe.ProbeResult r = composite.check();
        assertThat(r.healthy()).isFalse();
        assertThat(r.children()).hasSize(2);
        assertThat(r.details()).contains("p1=up", "p2=down");
    }

    @Test
    void compositeAll_runsAllProbesEvenAfterFailure() {
        boolean[] called = new boolean[3];
        com.authx.sdk.spi.HealthProbe composite = HealthProbe.all(
                () -> { called[0] = true; return ProbeResult.down("p1", Duration.ZERO, "f"); },
                () -> { called[1] = true; return ProbeResult.up("p2", Duration.ZERO, "ok"); },
                () -> { called[2] = true; return ProbeResult.up("p3", Duration.ZERO, "ok"); });
        composite.check();
        assertThat(called).containsExactly(true, true, true);
    }

    // ─── Composite: ANY (lenient OR) ─────────────────────────────────────

    @Test
    void compositeAny_oneUp_reportsUp() {
        com.authx.sdk.spi.HealthProbe composite = HealthProbe.any(
                () -> ProbeResult.down("p1", Duration.ZERO, "fail"),
                () -> ProbeResult.up("p2", Duration.ZERO, "ok"));
        com.authx.sdk.spi.HealthProbe.ProbeResult r = composite.check();
        assertThat(r.healthy()).isTrue();
    }

    @Test
    void compositeAny_allDown_reportsDown() {
        com.authx.sdk.spi.HealthProbe composite = HealthProbe.any(
                () -> ProbeResult.down("p1", Duration.ZERO, "a"),
                () -> ProbeResult.down("p2", Duration.ZERO, "b"));
        com.authx.sdk.spi.HealthProbe.ProbeResult r = composite.check();
        assertThat(r.healthy()).isFalse();
    }

    // ─── Empty / error cases ─────────────────────────────────────────────

    @Test
    void composite_requiresAtLeastOneProbe() {
        assertThatThrownBy(HealthProbe::all).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(HealthProbe::any).isInstanceOf(IllegalArgumentException.class);
    }

    // ─── Factories ───────────────────────────────────────────────────────

    @Test
    void upFactory_alwaysHealthy() {
        assertThat(HealthProbe.up().check().healthy()).isTrue();
    }

    @Test
    void downFactory_alwaysUnhealthy() {
        com.authx.sdk.spi.HealthProbe.ProbeResult r = HealthProbe.down("manual override").check();
        assertThat(r.healthy()).isFalse();
        assertThat(r.details()).isEqualTo("manual override");
    }

    @Test
    void defaultName_isClassSimpleName() {
        HealthProbe anonymous = () -> ProbeResult.up("x", Duration.ZERO, "");
        // Lambda class names are JVM-generated but non-empty
        assertThat(anonymous.name()).isNotBlank();
    }
}
