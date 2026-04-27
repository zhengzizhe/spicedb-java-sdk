package com.authx.sdk.telemetry;

import com.authx.sdk.spi.TelemetrySink;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TelemetryReporterTest {

    @Test
    void flush_sendsEventsToSink() {
        CopyOnWriteArrayList<List<Map<String, Object>>> received = new CopyOnWriteArrayList<List<Map<String, Object>>>();
        TelemetrySink sink = received::add;

        TelemetryReporter reporter = new TelemetryReporter(sink, 100, 10, 60_000, false);
        reporter.record("CHECK", "doc", "1", "user", "alice", "view", "ALLOWED", 5, "");
        reporter.flush();

        assertThat(received).hasSize(1);
        assertThat(received.getFirst()).hasSize(1);
        assertThat(received.getFirst().getFirst().get("action")).isEqualTo("CHECK");
        reporter.close();
    }

    @Test
    void sinkFailure_incrementsDroppedCount() {
        TelemetrySink failingSink = batch -> { throw new RuntimeException("sink down"); };

        TelemetryReporter reporter = new TelemetryReporter(failingSink, 100, 10, 60_000, false);
        reporter.record("CHECK", "doc", "1", "user", "alice", "view", "ALLOWED", 5, "");
        reporter.flush();

        assertThat(reporter.droppedEventCount()).isEqualTo(1);
        reporter.close();
    }

    @Test
    void bufferFull_incrementsBufferFullDropCount() {
        // Buffer capacity of 2
        TelemetryReporter reporter = new TelemetryReporter(TelemetrySink.NOOP, 2, 10, 60_000, false);
        reporter.record("A", "", "", "", "", "", "", 0, "");
        reporter.record("B", "", "", "", "", "", "", 0, "");
        reporter.record("C", "", "", "", "", "", "", 0, ""); // should be dropped

        assertThat(reporter.bufferFullDropCount()).isEqualTo(1);
        reporter.close();
    }

    @Test
    void close_flushesRemainingEvents() {
        CopyOnWriteArrayList<List<Map<String, Object>>> received = new CopyOnWriteArrayList<List<Map<String, Object>>>();
        TelemetrySink sink = received::add;

        TelemetryReporter reporter = new TelemetryReporter(sink, 100, 10, 60_000, false);
        reporter.record("CHECK", "doc", "1", "user", "alice", "view", "ALLOWED", 5, "");
        reporter.close();

        assertThat(received).isNotEmpty();
    }

    @Test
    void pendingCount_reflectsBufferSize() {
        TelemetryReporter reporter = new TelemetryReporter(TelemetrySink.NOOP, 100, 10, 60_000, false);
        assertThat(reporter.pendingCount()).isZero();

        reporter.record("A", "", "", "", "", "", "", 0, "");
        assertThat(reporter.pendingCount()).isEqualTo(1);

        reporter.flush();
        assertThat(reporter.pendingCount()).isZero();
        reporter.close();
    }
}
