package com.authx.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaveatContextContractTest {

    @Test
    void copiesAndExposesImmutableValues() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("client_ip", "10.0.0.5");

        CaveatContext context = CaveatContext.of(values);
        values.put("client_ip", "10.0.0.6");

        assertThat(context.values()).containsEntry("client_ip", "10.0.0.5");
        assertThat(context.asMap()).isEqualTo(context.values());
        assertThatThrownBy(() -> context.values().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullValuesMap() {
        assertThatThrownBy(() -> new CaveatContext(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("values");
    }

    @Test
    void rejectsBlankKeys() {
        assertThatThrownBy(() -> CaveatContext.of(Map.of("", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keys");
    }

    @Test
    void caveatRefAcceptsTypedContext() {
        CaveatRef ref = new CaveatRef("ip_allowlist",
                CaveatContext.of(Map.of("client_ip", "10.0.0.5")));

        assertThat(ref.name()).isEqualTo("ip_allowlist");
        assertThat(ref.context()).containsEntry("client_ip", "10.0.0.5");
    }
}
