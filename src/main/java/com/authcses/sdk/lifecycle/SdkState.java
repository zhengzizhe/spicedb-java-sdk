package com.authcses.sdk.lifecycle;

/**
 * SDK client lifecycle state.
 */
public enum SdkState {
    CREATED,        // Builder configured, not yet built
    STARTING,       // build() in progress
    RUNNING,        // Fully operational
    DEGRADED,       // Running but some subsystem unhealthy (e.g., watch disconnected)
    STOPPING,       // close() in progress
    STOPPED;        // Fully shut down

    public boolean isOperational() {
        return this == RUNNING || this == DEGRADED;
    }
}
