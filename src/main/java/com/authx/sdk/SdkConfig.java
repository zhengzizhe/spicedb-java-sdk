package com.authx.sdk;

import com.authx.sdk.policy.PolicyRegistry;
import java.util.Objects;

/** Immutable SDK configuration snapshot. */
public record SdkConfig(
    String defaultSubjectType,
    PolicyRegistry policies,
    boolean coalescingEnabled,
    boolean virtualThreads
) {
    public SdkConfig {
        Objects.requireNonNull(defaultSubjectType, "defaultSubjectType");
        Objects.requireNonNull(policies, "policies");
    }
}
