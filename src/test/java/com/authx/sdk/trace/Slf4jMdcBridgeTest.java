package com.authx.sdk.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class Slf4jMdcBridgeTest {

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void probe_slf4jPresent_returnsTrue() {
        // SLF4J is in testImplementation so this must be true in tests.
        assertThat(Slf4jMdcBridge.SLF4J_PRESENT).isTrue();
    }

    @Test
    void push_writesMdcKeys() throws Exception {
        try (Closeable c = Slf4jMdcBridge.push(Map.of(
                "authx.traceId", "abc",
                "authx.action", "CHECK"))) {
            assertThat(MDC.get("authx.traceId")).isEqualTo("abc");
            assertThat(MDC.get("authx.action")).isEqualTo("CHECK");
        }
    }

    @Test
    void close_popsAllPushedKeys() throws Exception {
        MDC.put("unrelated", "preserved");
        try (Closeable c = Slf4jMdcBridge.push(Map.of(
                "authx.traceId", "abc",
                "authx.action", "CHECK"))) {
            assertThat(MDC.get("authx.traceId")).isEqualTo("abc");
        }
        // Keys we pushed are gone.
        assertThat(MDC.get("authx.traceId")).isNull();
        assertThat(MDC.get("authx.action")).isNull();
        // Keys we didn't push are untouched.
        assertThat(MDC.get("unrelated")).isEqualTo("preserved");
    }

    @Test
    void push_nullFields_returnsNoop() throws Exception {
        Closeable c = Slf4jMdcBridge.push(null);
        assertThatCode(c::close).doesNotThrowAnyException();
    }

    @Test
    void push_emptyFields_returnsNoop() throws Exception {
        try (Closeable c = Slf4jMdcBridge.push(Map.of())) {
            // no-op: nothing pushed
        }
        assertThat(MDC.get("authx.traceId")).isNull();
    }

    @Test
    void push_allValuesNull_returnsNoop() throws Exception {
        // HashMap (not Map.of, which disallows null values)
        Map<String, String> fields = new HashMap<>();
        fields.put("authx.action", null);
        fields.put("authx.traceId", null);
        try (Closeable c = Slf4jMdcBridge.push(fields)) {
            // All entries skipped; MDC still clean
        }
        assertThat(MDC.get("authx.action")).isNull();
        assertThat(MDC.get("authx.traceId")).isNull();
    }

    @Test
    void push_closeTwice_idempotent() throws Exception {
        Closeable c = Slf4jMdcBridge.push(Map.of("authx.action", "CHECK"));
        c.close();
        assertThatCode(c::close).doesNotThrowAnyException();
        assertThat(MDC.get("authx.action")).isNull();
    }

    @Test
    void push_partialNullValues_skipped() throws Exception {
        Map<String, String> fields = new HashMap<>();
        fields.put("authx.action", "CHECK");
        fields.put("authx.subject", null);
        try (Closeable c = Slf4jMdcBridge.push(fields)) {
            assertThat(MDC.get("authx.action")).isEqualTo("CHECK");
            assertThat(MDC.get("authx.subject")).isNull();
        }
    }
}
