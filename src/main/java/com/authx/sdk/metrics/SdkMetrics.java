package com.authx.sdk.metrics;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * SDK internal metrics. Thread-safe, lock-free.
 *
 * <pre>
 * SdkMetrics m = client.metrics();
 * m.errorRate();           // 0.0–1.0
 * m.circuitBreakerState(); // "CLOSED"
 * m.snapshot();            // full snapshot for logging/export
 * </pre>
 *
 * <p>Note: cache hit/miss/eviction counters and watch-reconnect counters
 * were removed with the cache/Watch subsystems in ADR 2026-04-18.
 */
public class SdkMetrics {

    // ---- Requests ----
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final LongAdder coalescedRequests = new LongAdder();

    // ---- Latency overflow (SR:C9) ----
    // Count of recordRequest() samples that exceeded MAX_TRACKABLE_MICROS and
    // were clamped. Without this counter, extremely slow requests are silently
    // indistinguishable from requests that happened to clock at exactly the
    // clamp value — the tail of the percentile distribution is hidden. The
    // counter surfaces the event so operators can alert on it; the clamp
    // itself stays to bound HdrHistogram memory.
    private final LongAdder latencyOverflow = new LongAdder();

    // ---- Latency (HdrHistogram dual-buffer, microseconds) ----
    //
    // SR:C9 — raised from 60s to 600s (10 minutes). HdrHistogram memory cost
    // grows logarithmically in the trackable range at a fixed precision, so
    // moving the ceiling 10× adds only ~10KB while covering all realistic
    // request timeouts. Samples above this ceiling still clamp (bounding
    // memory) but also increment {@link #latencyOverflow} so they're visible.
    private static final long MAX_TRACKABLE_MICROS = 600_000_000L;
    private final Recorder recorder = new Recorder(MAX_TRACKABLE_MICROS, 3);
    private Histogram recycleBuffer;             // guarded by `this`
    private volatile Histogram publishedInterval;

    // ---- Circuit Breaker (injected via supplier) ----
    private volatile Supplier<String> circuitBreakerStateSupplier = () -> "N/A";

    // ---- Record methods (called by transport layers) ----

    public void recordRequest(long latencyMicros, boolean error) {
        totalRequests.increment();
        if (error) totalErrors.increment();
        // SR:C9 — count clamps separately so operators can alert on them.
        // A sample above MAX_TRACKABLE_MICROS indicates either a request that
        // genuinely took > 10 minutes (stuck thread, network black hole) or a
        // misuse of recordRequest with a bogus latency unit; either way it
        // deserves visibility distinct from "everything was ≤ 10min".
        if (latencyMicros > MAX_TRACKABLE_MICROS) {
            latencyOverflow.increment();
            recorder.recordValue(MAX_TRACKABLE_MICROS);
        } else {
            recorder.recordValue(latencyMicros);
        }
    }

    public void recordCoalesced() { coalescedRequests.increment(); }

    public void setCircuitBreakerStateSupplier(Supplier<String> supplier) {
        this.circuitBreakerStateSupplier = supplier;
    }

    // ---- Query methods (called by business code) ----

    public long totalRequests() { return totalRequests.sum(); }
    public long totalErrors() { return totalErrors.sum(); }
    public long coalescedRequests() { return coalescedRequests.sum(); }

    /**
     * Number of {@link #recordRequest} samples that exceeded the internal
     * latency clamp (≈ 10 minutes) and were therefore capped in the
     * percentile distribution. A non-zero value indicates requests that either
     * ran for an extremely long time (stuck thread, network black hole) or
     * were recorded with a bogus latency; alert on it to catch both cases.
     * See SR:C9.
     */
    public long latencyOverflowCount() { return latencyOverflow.sum(); }

    public double errorRate() {
        long total = totalRequests.sum();
        return total == 0 ? 0.0 : (double) totalErrors.sum() / total;
    }

    public String circuitBreakerState() {
        return circuitBreakerStateSupplier.get();
    }

    /** Full snapshot for logging or export. */
    public Snapshot snapshot() {
        Histogram h = publishedInterval;
        // Lazy first rotation: if no scheduled rotate has fired yet (common in
        // the first 5 seconds after startup, or in tests that don't wire a
        // scheduler), force one so the caller sees cumulative data instead of
        // a misleadingly-empty histogram. After this point rotateHistogram()
        // takes over on its 5s cadence.
        if (h == null) {
            synchronized (this) {
                if (publishedInterval == null) {
                    rotateHistogram();
                }
                h = publishedInterval;
            }
        }
        // O2 fix: distinguish "no data in this window" from "0ms latency". An
        // idle SDK between two rotations produces an empty histogram, and
        // HdrHistogram.getValueAtPercentile() returns 0 for empty — which is
        // indistinguishable from "everything was sub-microsecond fast".
        // Return Double.NaN so operators (and the toString() formatter below)
        // can tell the two cases apart.
        double p50, p95, p99, avg;
        if (h.getTotalCount() == 0) {
            p50 = p95 = p99 = avg = Double.NaN;
        } else {
            p50 = h.getValueAtPercentile(50.0) / 1000.0;
            p95 = h.getValueAtPercentile(95.0) / 1000.0;
            p99 = h.getValueAtPercentile(99.0) / 1000.0;
            avg = h.getMean() / 1000.0;
        }
        return new Snapshot(
                totalRequests(), totalErrors(), errorRate(), coalescedRequests(),
                p50, p95, p99, avg,
                circuitBreakerState(), latencyOverflowCount());
    }

    public record Snapshot(
            long totalRequests, long totalErrors, double errorRate, long coalescedRequests,
            double latencyP50Ms, double latencyP95Ms, double latencyP99Ms, double latencyAvgMs,
            String circuitBreakerState, long latencyOverflowCount
    ) {
        /** True iff the current rotation window contains no request samples. */
        public boolean latencyWindowEmpty() {
            return Double.isNaN(latencyP50Ms);
        }

        @Override
        public String toString() {
            String latencyStr = latencyWindowEmpty()
                    ? "latency=[no data in window]"
                    : String.format(
                            "latency=[p50=%.2fms p95=%.2fms p99=%.2fms avg=%.2fms]",
                            latencyP50Ms, latencyP95Ms, latencyP99Ms, latencyAvgMs);
            // SR:C9 — surface clamp count only when non-zero to avoid noise on
            // healthy deployments; a visible field signals "something unusual
            // happened in this window".
            String overflowStr = latencyOverflowCount > 0
                    ? String.format(", overflow=%d", latencyOverflowCount)
                    : "";
            return String.format(
                    "SdkMetrics{requests=%d, errors=%d (%.2f%%), coalesced=%d, " +
                    "%s, cb=%s%s}",
                    totalRequests, totalErrors, errorRate * 100, coalescedRequests,
                    latencyStr, circuitBreakerState, overflowStr);
        }
    }

    /**
     * Rotate the interval histogram. Called periodically by the scheduler
     * (every 5s by default) so that {@link #snapshot()} reads a stable,
     * fixed-window histogram instead of "everything since last read".
     *
     * <p>Drains the recorder into {@link #recycleBuffer} (reused to avoid
     * per-rotation allocation), then publishes a defensive copy so concurrent
     * readers can never observe a half-filled buffer.
     */
    public synchronized void rotateHistogram() {
        recycleBuffer = recorder.getIntervalHistogram(recycleBuffer);
        // copy() allocates ~per-rotation; at 5s cadence this is ~0.2 Hz which
        // is negligible. The alternative (handing out recycleBuffer directly)
        // would race against the next drain.
        publishedInterval = recycleBuffer.copy();
    }

}
