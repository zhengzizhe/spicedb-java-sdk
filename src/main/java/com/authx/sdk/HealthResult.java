package com.authx.sdk;

import com.authx.sdk.spi.HealthProbe;
import com.authx.sdk.spi.HealthProbe.ProbeResult;
import org.jspecify.annotations.Nullable;

/**
 * SDK health check result.
 *
 * <p>Returned from {@link AuthxClient#health()}. The top-level fields
 * ({@code spicedbHealthy}, {@code spicedbLatencyMs}, {@code details}) preserve
 * backwards compatibility with the original API. The new {@code probe} field
 * carries the full {@link ProbeResult} tree for diagnostic inspection
 * (useful when composed probes are used).
 */
public record HealthResult(
        boolean spicedbHealthy,
        long spicedbLatencyMs,
        String details,
        @Nullable ProbeResult probe
) {
    /** Backwards-compatible constructor (pre-{@code HealthProbe} SPI). */
    public HealthResult(boolean spicedbHealthy, long spicedbLatencyMs, String details) {
        this(spicedbHealthy, spicedbLatencyMs, details, null);
    }

    /** Lift a {@link ProbeResult} into a {@code HealthResult}. */
    public static HealthResult fromProbe(ProbeResult probe) {
        return new HealthResult(probe.healthy(), probe.latencyMs(), probe.details(), probe);
    }

    public boolean isHealthy() {
        return spicedbHealthy;
    }
}
