package com.authx.sdk.telemetry;

import com.authx.sdk.spi.TelemetrySink;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SR:C10 — A hung {@link TelemetrySink} must not block the reporter or
 * {@link TelemetryReporter#close()}. The per-sink-call timeout bounds both.
 */
class TelemetryReporterSinkTimeoutTest {

    /** Sink whose send() blocks on a latch the test never releases. */
    static final class HangingSink implements TelemetrySink {
        final CountDownLatch hang = new CountDownLatch(1);
        final AtomicInteger invocations = new AtomicInteger();
        @Override
        public void send(List<Map<String, Object>> batch) {
            invocations.incrementAndGet();
            try { hang.await(); }  // forever, unless the test releases
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    @Test
    void hung_sink_does_not_block_close() throws Exception {
        var sink = new HangingSink();
        var reporter = new TelemetryReporter(
                sink,
                /*bufferCapacity*/ 100,
                /*batchSize*/ 1,
                /*flushIntervalMs*/ 50,
                /*useVirtualThreads*/ false,
                /*sinkTimeout*/ Duration.ofMillis(200));

        // Fire enough events to trigger a scheduled flush. The flush will
        // submit to the sink executor which then blocks in send().
        for (int i = 0; i < 5; i++) {
            reporter.record("check", "document", "doc-" + i,
                    "user", "alice", "view", "allowed", 1, "trace");
        }

        // Give the scheduler a moment to fire a flush.
        Thread.sleep(400);

        long startNs = System.nanoTime();
        reporter.close();
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        // sinkTimeout=200ms, plus the close()'s scheduler.awaitTermination(5s)
        // budget plus the sinkExecutor.awaitTermination(sinkTimeout) — in the
        // worst case close takes ~5.4s. We assert an upper bound of 8s so
        // the test has slack on a slow CI machine while still catching the
        // "hangs forever" regression.
        assertTrue(elapsedMs < 8_000,
                "close() should return within 8s; took " + elapsedMs + "ms");

        // The sink was invoked at least once (proving the scheduled flush ran).
        assertTrue(sink.invocations.get() >= 1,
                "sink.send() should have been invoked at least once");
        // And the reporter recorded at least one timeout.
        assertTrue(reporter.sinkTimeoutCount() >= 1,
                "sinkTimeoutCount should be >= 1; was " + reporter.sinkTimeoutCount());

        // Release so the background thread can finish cleanly when the
        // sink executor is shut down.
        sink.hang.countDown();
    }

    @Test
    void fast_sink_records_no_timeouts() throws Exception {
        var seen = new AtomicInteger();
        TelemetrySink fast = batch -> seen.addAndGet(batch.size());
        var reporter = new TelemetryReporter(
                fast, 100, 1, 50, false, Duration.ofSeconds(2));

        for (int i = 0; i < 3; i++) {
            reporter.record("check", "document", "doc-" + i,
                    "user", "alice", "view", "allowed", 1, "trace");
        }

        // Give the scheduler a couple of intervals to drain.
        Thread.sleep(200);
        reporter.close();

        assertEquals(0, reporter.sinkTimeoutCount(),
                "healthy sink must record no timeouts");
        assertTrue(seen.get() >= 3,
                "all 3 events should be delivered to the sink; saw " + seen.get());
    }
}
