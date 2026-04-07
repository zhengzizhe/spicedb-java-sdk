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

    void flush() {
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
        scheduler.shutdown();
        flush();
        try { scheduler.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public int pendingCount() { return buffer.size(); }
}
