package com.authx.sdk.spi;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link SdkComponents} record and its builder. Focuses on the
 * wire-up guarantees the rest of the SDK relies on — namely, that each
 * Builder setter actually stores its argument in the resulting record.
 */
class SdkComponentsTest {

    @Test
    void defaults_hasNoopsAndNulls() {
        var defaults = SdkComponents.defaults();
        assertThat(defaults.telemetrySink()).isEqualTo(TelemetrySink.NOOP);
        assertThat(defaults.clock()).isEqualTo(SdkClock.SYSTEM);
        assertThat(defaults.tokenStore()).isNull();
        assertThat(defaults.healthProbe()).isNull();
        assertThat(defaults.watchDuplicateDetector()).isNull();
    }

    @Test
    void builder_storesWatchDuplicateDetector() {
        DuplicateDetector<String> detector = key -> true;
        var components = SdkComponents.builder()
                .watchDuplicateDetector(detector)
                .build();

        // Identity match — not just "something non-null"
        assertThat(components.watchDuplicateDetector()).isSameAs(detector);
    }

    @Test
    void builder_storesHealthProbe() {
        HealthProbe probe = HealthProbe.up();
        var components = SdkComponents.builder()
                .healthProbe(probe)
                .build();

        assertThat(components.healthProbe()).isSameAs(probe);
    }

    @Test
    void builder_fieldsAreIndependent() {
        // Each setter should only affect its own field — no cross-contamination.
        HealthProbe probe = HealthProbe.up();
        DuplicateDetector<String> detector = key -> true;

        var components = SdkComponents.builder()
                .healthProbe(probe)
                .watchDuplicateDetector(detector)
                .build();

        assertThat(components.healthProbe()).isSameAs(probe);
        assertThat(components.watchDuplicateDetector()).isSameAs(detector);
        assertThat(components.tokenStore()).isNull();
        assertThat(components.telemetrySink()).isEqualTo(TelemetrySink.NOOP);
    }

    @Test
    void builder_lruDedupFactoryReturnsFunctioningDetector() {
        // Sanity check the static factory — doesn't actually dedupe yet,
        // but confirms the bridge to CaffeineDuplicateDetector works.
        DuplicateDetector<String> detector = DuplicateDetector.lru(100, Duration.ofMinutes(1));
        assertThat(detector.tryProcess("a")).isTrue();
        assertThat(detector.tryProcess("a")).isFalse();
        assertThat(detector.tryProcess("b")).isTrue();
    }
}
