package com.authx.testapp;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.policy.CachePolicy;
import com.authx.sdk.policy.CircuitBreakerPolicy;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ReadConsistency;
import com.authx.sdk.policy.ResourcePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class SpiceDbConfig {

    @Value("${spicedb.targets}")
    private String targets;

    @Value("${spicedb.preshared-key}")
    private String presharedKey;

    @Value("${spicedb.cache.enabled}")
    private boolean cacheEnabled;

    @Value("${spicedb.cache.max-size}")
    private long cacheMaxSize;

    @Value("${spicedb.watch-invalidation}")
    private boolean watchInvalidation;

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
                .cache(c -> c
                        .enabled(cacheEnabled)
                        .maxSize(cacheMaxSize)
                        .watchInvalidation(watchInvalidation))
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
        // manually. The tradeoff vs minimizeLatency is one extra zedToken
        // field per request and a few hundred microseconds of server-side
        // dispatch overhead. Fine for the demo.
        return PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .circuitBreaker(CircuitBreakerPolicy.disabled())
                        .cache(CachePolicy.of(Duration.ofSeconds(30)))
                        .readConsistency(ReadConsistency.session())
                        .build())
                .build();
    }
}
