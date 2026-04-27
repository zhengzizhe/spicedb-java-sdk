package com.authx.sdk.spi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link SdkComponents} record and its builder. Focuses on the
 * wire-up guarantees the rest of the SDK relies on — namely, that each
 * Builder setter actually stores its argument in the resulting record.
 */
class SdkComponentsTest {

    @Test
    void defaults_hasNoopsAndNulls() {
        com.authx.sdk.spi.SdkComponents defaults = SdkComponents.defaults();
        assertThat(defaults.telemetrySink()).isEqualTo(TelemetrySink.NOOP);
        assertThat(defaults.clock()).isEqualTo(SdkClock.SYSTEM);
        assertThat(defaults.tokenStore()).isNull();
        assertThat(defaults.healthProbe()).isNull();
    }

    @Test
    void builder_storesHealthProbe() {
        HealthProbe probe = HealthProbe.up();
        com.authx.sdk.spi.SdkComponents components = SdkComponents.builder()
                .healthProbe(probe)
                .build();

        assertThat(components.healthProbe()).isSameAs(probe);
    }

    @Test
    void builder_fieldsAreIndependent() {
        // Each setter should only affect its own field — no cross-contamination.
        HealthProbe probe = HealthProbe.up();

        com.authx.sdk.spi.SdkComponents components = SdkComponents.builder()
                .healthProbe(probe)
                .build();

        assertThat(components.healthProbe()).isSameAs(probe);
        assertThat(components.tokenStore()).isNull();
        assertThat(components.telemetrySink()).isEqualTo(TelemetrySink.NOOP);
    }
}
