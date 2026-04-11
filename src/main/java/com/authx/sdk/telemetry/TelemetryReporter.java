package com.authx.sdk.telemetry;

import com.authx.sdk.spi.TelemetrySink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Async telemetry reporter: buffers SDK events in memory,
 * flushes to TelemetrySink periodically or when buffer is full.
 *
 * <p>V2: no platform dependency. Uses TelemetrySink SPI.
 * Default sink is noop. Business code provides their own (Kafka, OTLP, file, etc.)
 */
public class TelemetryReporter implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(TelemetryReporter.class.getName());

    private final TelemetrySink sink;
    private final int batchSize;
    private final LinkedBlockingQueue<Map<String, Object>> buffer;
    private final ScheduledExecutorService scheduler;
    private final java.util.concurrent.atomic.AtomicBoolean flushScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.LongAdder droppedEvents = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder bufferFullDrops = new java.util.concurrent.atomic.LongAdder();
    private volatile int consecutiveFlushFailures = 0;

    public TelemetryReporter(TelemetrySink sink, int bufferCapacity, int batchSize,
                             long flushIntervalMs, boolean useVirtualThreads) {
        this.sink = sink != null ? sink : TelemetrySink.NOOP;
        this.batchSize = batchSize;
        this.buffer = new LinkedBlockingQueue<>(bufferCapacity);
        ThreadFactory tf = useVirtualThreads
                ? Thread.ofVirtual().name("authx-sdk-telemetry-", 0).factory()
                : r -> { Thread t = new Thread(r, "authx-sdk-telemetry"); t.setDaemon(true); return t; };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        this.scheduler.scheduleAtFixedRate(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    public TelemetryReporter(TelemetrySink sink, boolean useVirtualThreads) {
        this(sink, 10_000, 100, 5_000, useVirtualThreads);
    }

    public void record(String action, String resourceType, String resourceId,
                       String subjectType, String subjectId, String permission,
                       String result, long latencyMs, String traceId) {
        var event = Map.<String, Object>of(
                "action", action,
                "resourceType", resourceType != null ? resourceType : "",
                "resourceId", resourceId != null ? resourceId : "",
                "subjectType", subjectType != null ? subjectType : "",
                "subjectId", subjectId != null ? subjectId : "",
                "permission", permission != null ? permission : "",
                "result", result != null ? result : "",
                "latencyMs", latencyMs,
                "traceId", traceId != null ? traceId : ""
        );

        if (!buffer.offer(event)) {
            bufferFullDrops.increment();
        }

        if (buffer.size() >= batchSize && flushScheduled.compareAndSet(false, true)) {
            scheduler.execute(() -> {
                try { flush(); }
                finally { flushScheduled.set(false); }
            });
        }
    }

    /**
     * Drain the buffer and deliver a batch to the sink.
     *
     * <p>F12-3: {@code synchronized} serializes the scheduled periodic flush
     * against any manual flush (in particular the one triggered by
     * {@link #close()}). Without this, both flushes would race on
     * {@code buffer.drainTo()} — which is individually thread-safe but can
     * return two disjoint batches that get passed to {@code sink.send()}
     * concurrently. A user-supplied {@code TelemetrySink} is not guaranteed
     * to be thread-safe (Kafka producers and file writers often aren't),
     * so we synchronize at the reporter level to give sinks a
     * one-caller-at-a-time contract.
     */
    synchronized void flush() {
        if (buffer.isEmpty()) return;

        List<Map<String, Object>> batch = new ArrayList<>(Math.min(buffer.size(), batchSize * 2));
        buffer.drainTo(batch, batchSize * 2);
        if (batch.isEmpty()) return;

        try {
            sink.send(batch);
            consecutiveFlushFailures = 0;
        } catch (Exception e) {
            droppedEvents.add(batch.size());
            consecutiveFlushFailures++;
            if (consecutiveFlushFailures <= 3) {
                LOG.log(System.Logger.Level.WARNING, "Telemetry flush failed ({0} events dropped): {1}",
                        batch.size(), e.getMessage());
            } else if (consecutiveFlushFailures == 4) {
                LOG.log(System.Logger.Level.WARNING,
                        "Telemetry sink consistently failing, suppressing further warnings (total dropped: {0})",
                        droppedEvents.sum());
            }
            // After 4 failures, stop logging but keep trying (sink may recover)
        }
    }

    /** Total number of events dropped due to sink failures. */
    public long droppedEventCount() { return droppedEvents.sum(); }

    /** Events dropped because the internal buffer was full (separate from sink failures). */
    public long bufferFullDropCount() { return bufferFullDrops.sum(); }

    @Override
    public void close() {
        // Shutdown order (F12-3): stop accepting new scheduled work, wait for
        // any in-flight flush to finish (its scheduler thread is still alive
        // because shutdown() is a graceful request), then do a final flush
        // from the calling thread. The synchronized modifier on flush() is
        // what makes the "in-flight flush finishes before ours starts" step
        // safe — otherwise we'd race sink.send() with a scheduled flush.
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                // Scheduled flush is taking too long — force interrupt and
                // move on. The final flush below will deliver whatever's
                // still in the buffer.
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        // Final drain from the calling thread. Safe to call after
        // shutdownNow() because flush() only touches buffer + sink, not the
        // scheduler itself.
        flush();
    }

    public int pendingCount() { return buffer.size(); }
}
