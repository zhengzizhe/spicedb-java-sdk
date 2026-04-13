package com.authx.sdk.transport;

import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.*;
import com.authx.sdk.telemetry.TelemetryReporter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link InstrumentedTransport} — telemetry and metrics recording.
 */
class InstrumentedTransportTest {

    private InMemoryTransport inner;
    private SdkMetrics metrics;
    private List<Map<String, Object>> recordedEvents;
    private TelemetryReporter reporter;

    @BeforeEach
    void setup() {
        inner = new InMemoryTransport();
        metrics = new SdkMetrics();
        recordedEvents = new ArrayList<>();

        // Use a TelemetryReporter with a collecting sink
        reporter = new TelemetryReporter(batch -> recordedEvents.addAll(batch),
                10_000, 1, 60_000, false);

        // Pre-populate: alice is editor on document:d1
        inner.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));
    }

    @Test
    void checkRecordsMetricsAndTelemetry() {
        var transport = new InstrumentedTransport(inner, reporter, metrics);

        var result = transport.check(CheckRequest.of(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        assertThat(result.hasPermission()).isTrue();
        assertThat(metrics.totalRequests()).isEqualTo(1);
        assertThat(metrics.totalErrors()).isZero();

        // Flush the reporter to capture events
        reporter.close();

        assertThat(recordedEvents).hasSize(1);
        var event = recordedEvents.getFirst();
        assertThat(event.get("action")).isEqualTo("CHECK");
        assertThat(event.get("resourceType")).isEqualTo("document");
        assertThat(event.get("result")).isEqualTo("HAS_PERMISSION");
    }

    @Test
    void checkRecordsErrorOnException() {
        SdkTransport failing = new InMemoryTransport() {
            @Override
            public CheckResult check(CheckRequest request) {
                throw new RuntimeException("connection refused");
            }
        };

        var transport = new InstrumentedTransport(failing, reporter, metrics);

        assertThatThrownBy(() -> transport.check(CheckRequest.of(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency())))
                .isInstanceOf(RuntimeException.class);

        assertThat(metrics.totalRequests()).isEqualTo(1);
        assertThat(metrics.totalErrors()).isEqualTo(1);
    }

    @Test
    void writeRelationshipsRecordsMetrics() {
        var transport = new InstrumentedTransport(inner, reporter, metrics);

        var result = transport.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d2"),
                Relation.of("viewer"),
                SubjectRef.of("user", "bob", null))));

        assertThat(result.count()).isEqualTo(1);
        assertThat(metrics.totalRequests()).isEqualTo(1);
        assertThat(metrics.totalErrors()).isZero();
    }

    @Test
    void deleteRelationshipsRecordsMetrics() {
        // First write, then delete
        inner.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d2"),
                Relation.of("viewer"),
                SubjectRef.of("user", "bob", null))));

        var transport = new InstrumentedTransport(inner, reporter, metrics);

        var result = transport.deleteRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.DELETE,
                ResourceRef.of("document", "d2"),
                Relation.of("viewer"),
                SubjectRef.of("user", "bob", null))));

        assertThat(result.count()).isEqualTo(1);
        assertThat(metrics.totalRequests()).isEqualTo(1);
    }

    @Test
    void lookupSubjectsRecordsMetrics() {
        var transport = new InstrumentedTransport(inner, reporter, metrics);

        var subjects = transport.lookupSubjects(new LookupSubjectsRequest(
                ResourceRef.of("document", "d1"),
                Permission.of("editor"),
                "user"));

        assertThat(subjects).hasSize(1);
        assertThat(metrics.totalRequests()).isEqualTo(1);
    }

    @Test
    void lookupResourcesRecordsMetrics() {
        var transport = new InstrumentedTransport(inner, reporter, metrics);

        var resources = transport.lookupResources(new LookupResourcesRequest(
                "document",
                Permission.of("editor"),
                SubjectRef.of("user", "alice", null)));

        assertThat(resources).hasSize(1);
        assertThat(metrics.totalRequests()).isEqualTo(1);
    }

    @Test
    void readRelationshipsRecordsMetrics() {
        var transport = new InstrumentedTransport(inner, reporter, metrics);

        var tuples = transport.readRelationships(
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                Consistency.minimizeLatency());

        assertThat(tuples).hasSize(1);
        assertThat(metrics.totalRequests()).isEqualTo(1);
    }

    @Test
    void readRelationshipsWithNullRelation() {
        var transport = new InstrumentedTransport(inner, reporter, metrics);

        var tuples = transport.readRelationships(
                ResourceRef.of("document", "d1"),
                null,
                Consistency.minimizeLatency());

        assertThat(tuples).hasSize(1);
        assertThat(metrics.totalRequests()).isEqualTo(1);
    }

    @Test
    void nullMetricsDoesNotThrow() {
        var transport = new InstrumentedTransport(inner, reporter, null);

        var result = transport.check(CheckRequest.of(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        assertThat(result.hasPermission()).isTrue();
    }

    @Test
    void closeDelegates() {
        var transport = new InstrumentedTransport(inner, reporter, metrics);
        transport.close();
        assertThat(inner.size()).isZero();
    }
}
