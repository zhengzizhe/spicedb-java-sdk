package com.authx.testapp;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.exception.AuthxAuthException;
import com.authx.sdk.exception.AuthxInvalidArgumentException;
import com.authx.sdk.exception.AuthxPreconditionException;
import com.authx.sdk.exception.AuthxResourceExhaustedException;
import com.authx.sdk.exception.AuthxUnimplementedException;
import com.authx.sdk.exception.CircuitBreakerOpenException;
import com.authx.sdk.exception.InvalidPermissionException;
import com.authx.sdk.exception.InvalidRelationException;
import com.authx.sdk.exception.InvalidResourceException;
import com.authx.sdk.policy.CircuitBreakerPolicy;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ReadConsistency;
import com.authx.sdk.policy.ResourcePolicy;
import com.authx.sdk.policy.RetryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

@Configuration
@Profile("!inmem")
public class SpiceDbConfig {

    @Value("${spicedb.targets}")
    private String targets;

    @Value("${spicedb.preshared-key}")
    private String presharedKey;

    @Value("${spicedb.virtual-threads}")
    private boolean virtualThreads;

    @Bean(destroyMethod = "close")
    public AuthxClient authxClient() {
        String[] addrs = targets.split(",");
        return AuthxClient.builder()
                .connection(c -> c
                        .targets(addrs)
                        .presharedKey(presharedKey)
                        .requestTimeout(Duration.ofSeconds(10)))
                .features(f -> f
                        .virtualThreads(virtualThreads)
                        .telemetry(true)
                        .shutdownHook(true))
                .extend(e -> e.policies(policies()))
                .build();
    }

    private PolicyRegistry policies() {
        // SESSION consistency — the SDK's TokenTracker auto-chains zedTokens
        // from writes into subsequent reads within the same JVM, so
        // "onboard a user and immediately check if they can view the welcome
        // doc" just works without the business code having to thread tokens
        // manually. A few hundred microseconds of server-side dispatch
        // overhead vs MinimizeLatency.
        return PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .circuitBreaker(CircuitBreakerPolicy.disabled())
                        .readConsistency(ReadConsistency.session())
                        .retry(retryPolicyWithBaseDelay(Duration.ofMillis(50)))
                        .build())
                .build();
    }

    /**
     * RetryPolicy with a caller-controlled {@code baseDelay} plus the same
     * non-retryable exception list that {@link RetryPolicy#defaults()} uses.
     *
     * <p>Hand-rolled because {@code RetryPolicy.builder()} starts from a blank
     * slate — if we just set {@code baseDelay} and called {@code build()}, the
     * permanent-error denylist would be empty and the SDK would retry
     * {@code AuthxAuthException} (401/403) and other permanent errors. Mirroring
     * {@code defaults()} here keeps the safety guarantees.
     */
    private static RetryPolicy retryPolicyWithBaseDelay(Duration baseDelay) {
        return RetryPolicy.builder()
                .maxAttempts(3)
                // maxDelay must be >= baseDelay * multiplier^(maxAttempts-2)
                // so it doesn't silently truncate the last backoff.
                .baseDelay(baseDelay)
                .maxDelay(baseDelay.multipliedBy(4))
                .multiplier(2.0)
                .jitterFactor(0.2)
                .doNotRetryOn(CircuitBreakerOpenException.class)
                .doNotRetryOn(AuthxAuthException.class)
                .doNotRetryOn(AuthxResourceExhaustedException.class)
                .doNotRetryOn(AuthxInvalidArgumentException.class)
                .doNotRetryOn(AuthxUnimplementedException.class)
                .doNotRetryOn(AuthxPreconditionException.class)
                .doNotRetryOn(InvalidPermissionException.class)
                .doNotRetryOn(InvalidRelationException.class)
                .doNotRetryOn(InvalidResourceException.class)
                .build();
    }
}
