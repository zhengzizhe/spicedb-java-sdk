package com.authx.sdk.model.enums;

/**
 * Outcome of an SDK operation, used for telemetry recording and metrics labeling.
 */
public enum OperationResult {
    /** The operation completed successfully. */
    SUCCESS,
    /** The operation failed with an error. */
    ERROR;
}
