package com.authx.sdk.telemetry;

import com.authx.sdk.spi.TelemetrySink;

import java.time.Duration;
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

    /** SR:C10 — Default timeout for a single sink.send() invocation. */
    public static final Duration DEFAULT_SINK_TIMEOUT = Duration.ofSeconds(5);

    private final TelemetrySink sink;
    private final int batchSize;
    private final LinkedBlockingQueue<Map<String, Object>> buffer;
    private final ScheduledExecutorService scheduler;
    /**
     * SR:C10 — dedicated executor for the (potentially slow) sink call so that
     * a hung sink cannot block the scheduler thread. One thread is enough: the
     * synchronized {@link #flush()} already serializes calls to sink.send().
     */
    private final ExecutorService sinkExecutor;
    private final Duration sinkTimeout;
    private final java.util.concurrent.atomic.AtomicBoolean flushScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.LongAdder droppedEvents = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder bufferFullDrops = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder sinkTimeouts = new java.util.concurrent.atomic.LongAdder();
    private volatile int consecutiveFlushFailures = 0;

    public TelemetryReporter(TelemetrySink sink, int bufferCapacity, int batchSize,
                             long flushIntervalMs, boolean useVirtualThreads) {
        this(sink, bufferCapacity, batchSize, flushIntervalMs, useVirtualThreads, DEFAULT_SINK_TIMEOUT);
    }

    /**
     * Full-control constructor with configurable sink timeout (SR:C10).
     * A hung {@link TelemetrySink} no longer blocks the reporter's scheduler
     * or {@link #close()}; batches that exceed {@code sinkTimeout} count as
     * dropped and the scheduler moves on.
     */
    public TelemetryReporter(TelemetrySink sink, int bufferCapacity, int batchSize,
                             long flushIntervalMs, boolean useVirtualThreads,
                             Duration sinkTimeout) {
        this.sink = sink != null ? sink : TelemetrySink.NOOP;
        this.batchSize = batchSize;
        this.sinkTimeout = sinkTimeout != null ? sinkTimeout : DEFAULT_SINK_TIMEOUT;
        this.buffer = new LinkedBlockingQueue<>(bufferCapacity);
        ThreadFactory schedulerTf = useVirtualThreads
                ? Thread.ofVirtual().name("authx-sdk-telemetry-", 0).factory()
                : r -> { Thread t = new Thread(r, "authx-sdk-telemetry"); t.setDaemon(true); return t; };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(schedulerTf);
        ThreadFactory sinkTf = useVirtualThreads
                ? Thread.ofVirtual().name("authx-sdk-telemetry-sink-", 0).factory()
                : r -> { Thread t = new Thread(r, "authx-sdk-telemetry-sink"); t.setDaemon(true); return t; };
        this.sinkExecutor = Executors.newSingleThreadExecutor(sinkTf);
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

        // SR:C10 — bound the sink call. Delegate to sinkExecutor so we can
        // wait with a timeout without blocking the scheduler thread
        // indefinitely. If the sink takes longer than sinkTimeout, the batch
        // is counted as dropped; the orphaned sink call may eventually
        // complete (we do not interrupt it — interrupting mid-flight I/O
        // risks corrupting user-held state such as a Kafka producer buffer).
        CompletableFuture<Void> inflight = CompletableFuture.runAsync(
                () -> sink.send(batch), sinkExecutor);
        try {
            inflight.get(sinkTimeout.toMillis(), TimeUnit.MILLISECONDS);
            consecutiveFlushFailures = 0;
        } catch (TimeoutException te) {
            sinkTimeouts.increment();
            droppedEvents.add(batch.size());
            consecutiveFlushFailures++;
            if (consecutiveFlushFailures <= 3) {
                LOG.log(System.Logger.Level.WARNING,
                        "Telemetry sink timed out after {0}ms ({1} events dropped)",
                        sinkTimeout.toMillis(), batch.size());
            }
            // Let the underlying CompletableFuture keep running so the sink's
            // I/O completes naturally — we just stopped waiting for it.
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            droppedEvents.add(batch.size());
        } catch (ExecutionException ee) {
            droppedEvents.add(batch.size());
            consecutiveFlushFailures++;
            Throwable cause = ee.getCause();
            if (consecutiveFlushFailures <= 3) {
                LOG.log(System.Logger.Level.WARNING, "Telemetry flush failed ({0} events dropped): {1}",
                        batch.size(), cause != null ? cause.getMessage() : ee.getMessage());
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

    /**
     * Number of sink.send() invocations that exceeded {@link #DEFAULT_SINK_TIMEOUT}
     * (or the timeout supplied to the constructor) and were abandoned. See SR:C10.
     */
    public long sinkTimeoutCount() { return sinkTimeouts.sum(); }

    @Override
    public void close() {
        // Shutdown order (F12-3 + SR:C10): stop accepting new scheduled work,
        // wait for any in-flight flush to finish (bounded by scheduler
        // termination AND the per-sink-call timeout), do a final flush from
        // the calling thread, then shut down the sink executor with the same
        // timeout so a hung sink cannot pin close() indefinitely.
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        // Final drain from the calling thread. flush() itself is bounded by
        // sinkTimeout, so this returns promptly even with a hung sink.
        flush();

        // SR:C10 — shut down the sink executor. Wait sinkTimeout for a
        // currently-running send to finish, then force.
        sinkExecutor.shutdown();
        try {
            if (!sinkExecutor.awaitTermination(sinkTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                sinkExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sinkExecutor.shutdownNow();
        }
    }

    public int pendingCount() { return buffer.size(); }
}
