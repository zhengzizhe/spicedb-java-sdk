package com.authcses.testapp;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.sdk.policy.CachePolicy;
import com.authcses.sdk.policy.CircuitBreakerPolicy;
import com.authcses.sdk.policy.PolicyRegistry;
import com.authcses.sdk.policy.ResourcePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class SpiceDbConfig {

    @Value("${spicedb.target}")
    private String target;

    @Value("${spicedb.target-secondary}")
    private String targetSecondary;

    @Value("${spicedb.preshared-key}")
    private String presharedKey;

    @Value("${spicedb.cache-enabled}")
    private boolean cacheEnabled;

    @Value("${spicedb.cache-max-size}")
    private long cacheMaxSize;

    @Value("${spicedb.watch-invalidation}")
    private boolean watchInvalidation;

    @Value("${spicedb.virtual-threads}")
    private boolean virtualThreads;

    /** Load-test PolicyRegistry: disabled circuit breaker + cache enabled */
    private PolicyRegistry loadTestPolicies() {
        return PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .circuitBreaker(CircuitBreakerPolicy.disabled())
                        .cache(CachePolicy.ofTtl(Duration.ofSeconds(30)))
                        .build())
                .build();
    }

    /** Primary SDK client — connects to spicedb-1. */
    @Bean(destroyMethod = "close")
    @org.springframework.context.annotation.Primary
    public AuthCsesClient primaryClient() {
        return AuthCsesClient.builder()
                .connection(c -> c.target(target).presharedKey(presharedKey)
                        .requestTimeout(Duration.ofSeconds(30)))
                .cache(c -> c.enabled(cacheEnabled).maxSize(cacheMaxSize)
                        .watchInvalidation(watchInvalidation))
                .features(f -> f.virtualThreads(virtualThreads))
                .extend(e -> e.policies(loadTestPolicies()))
                .build();
    }

    /** Secondary SDK client — connects to spicedb-2 (for multi-instance testing). */
    @Bean(name = "secondaryClient", destroyMethod = "close")
    public AuthCsesClient secondaryClient() {
        return AuthCsesClient.builder()
                .connection(c -> c.target(targetSecondary).presharedKey(presharedKey)
                        .requestTimeout(Duration.ofSeconds(30)))
                .cache(c -> c.enabled(cacheEnabled).maxSize(cacheMaxSize)
                        .watchInvalidation(watchInvalidation))
                .features(f -> f.virtualThreads(virtualThreads))
                .extend(e -> e.policies(loadTestPolicies()))
                .build();
    }
}
