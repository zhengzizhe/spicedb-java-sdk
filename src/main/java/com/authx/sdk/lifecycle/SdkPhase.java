package com.authx.sdk.lifecycle;

/**
 * SDK startup phases, executed in order.
 */
public enum SdkPhase {
    CHANNEL,        // Create gRPC channel to SpiceDB
    TRANSPORT,      // Build transport decorator chain
    SCHEMA,         // Load and validate schema
    WATCH,          // Start Watch stream (if enabled)
    SCHEDULER,      // Start refresh scheduler
    READY;          // All subsystems initialized
}
