package com.authx.sdk.internal;

import com.authx.sdk.policy.PolicyRegistry;
import java.util.Objects;

/** Immutable SDK configuration snapshot. */
public record SdkConfig(
    PolicyRegistry policies,
    boolean coalescingEnabled,
    boolean virtualThreads
) {
    public SdkConfig {
        Objects.requireNonNull(policies, "policies");
    }
}
