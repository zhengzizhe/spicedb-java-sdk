package com.authx.sdk;

/**
 * SDK health check result.
 */
public record HealthResult(
        boolean spicedbHealthy,
        long spicedbLatencyMs,
        String details
) {
    public boolean isHealthy() {
        return spicedbHealthy;
    }
}
