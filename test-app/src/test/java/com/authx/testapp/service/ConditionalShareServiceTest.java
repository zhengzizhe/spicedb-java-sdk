package com.authx.testapp.service;

import com.authx.sdk.model.CaveatRef;
import com.authx.testapp.schema.Caveats;
import com.authx.testapp.schema.IpAllowlist;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the typed-caveat surface that {@link ConditionalShareService}
 * depends on:
 * <ol>
 *   <li>Generated constants match the SpiceDB caveat spelling — a typo
 *       in {@code IpAllowlist.CIDRS} would break the wire call silently
 *       because SpiceDB identifies parameters by name.</li>
 *   <li>{@link IpAllowlist#ref(Object...)} builds a {@link CaveatRef}
 *       with the right {@code name} and the exact key-value pairs the
 *       caller supplied.</li>
 *   <li>{@link IpAllowlist#context(Object...)} returns a plain
 *       {@code Map<String, Object>} (no wrapping / copy) with the right
 *       shape so the check-side {@code withContext(...)} path accepts it.</li>
 *   <li>Parameter-count / key-type mistakes fail fast with a clear
 *       exception, not a later opaque SpiceDB error.</li>
 * </ol>
 *
 * <p>No live SpiceDB — pure unit tests on the codegen output. The
 * actual CEL evaluation is the server's job and is covered by the
 * cluster-test harness.
 */
class ConditionalShareServiceTest {

    @Test
    void caveatNameMatchesSchema() {
        assertThat(IpAllowlist.NAME).isEqualTo("ip_allowlist");
        assertThat(Caveats.IP_ALLOWLIST).isEqualTo("ip_allowlist");
    }

    @Test
    void parameterNameConstantsMatchSchema() {
        assertThat(IpAllowlist.CIDRS).isEqualTo("cidrs");
        assertThat(IpAllowlist.CLIENT_IP).isEqualTo("client_ip");
    }

    @Test
    void refBuildsCaveatRefWithStaticParameter() {
        CaveatRef ref = IpAllowlist.ref(
                IpAllowlist.CIDRS, List.of("10.0.0.0/8", "172.16.0.0/12"));

        assertThat(ref.name()).isEqualTo("ip_allowlist");
        assertThat(ref.context()).containsEntry(
                "cidrs", List.of("10.0.0.0/8", "172.16.0.0/12"));
        assertThat(ref.context()).hasSize(1);
    }

    @Test
    void contextBuildsEvaluationPayload() {
        Map<String, Object> ctx = IpAllowlist.context(IpAllowlist.CLIENT_IP, "10.5.42.7");

        assertThat(ctx).containsExactly(
                java.util.Map.entry("client_ip", "10.5.42.7"));
    }

    @Test
    void refRejectsOddKeyValueCount() {
        assertThatThrownBy(() -> IpAllowlist.ref(IpAllowlist.CIDRS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("even length");
    }

    @Test
    void contextRejectsNonStringKey() {
        assertThatThrownBy(() -> IpAllowlist.context(42, "something"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Key at index 0 must be a String");
    }

    // ─── Service-surface smoke — the service's method signatures stay
    //     stable across refactors of the underlying typed chain. If a
    //     signature drifts, this test fails at compile time, not in
    //     production against a live SpiceDB.
    @Test
    void serviceMethodsHaveExpectedSignatures() throws NoSuchMethodException {
        assertThat(ConditionalShareService.class.getDeclaredMethod(
                "shareOnCorpNetwork", String.class, List.class)).isNotNull();
        assertThat(ConditionalShareService.class.getDeclaredMethod(
                "stopSharing", String.class)).isNotNull();
        assertThat(ConditionalShareService.class.getDeclaredMethod(
                "canOpenFrom", String.class, String.class, String.class)).isNotNull();
    }
}
