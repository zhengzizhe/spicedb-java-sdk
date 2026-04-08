package com.authx.cluster.config;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.policy.CachePolicy;
import com.authx.sdk.policy.CircuitBreakerPolicy;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ResourcePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class SdkConfig {

    @Value("${spicedb.targets}")
    private String targets;

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

    @Bean(destroyMethod = "close")
    public AuthxClient authxClient() {
        String[] targetList = targets.split(",");
        return AuthxClient.builder()
                .connection(c -> c
                        .targets(targetList)
                        .presharedKey(presharedKey)
                        .requestTimeout(Duration.ofSeconds(30)))
                .cache(c -> c
                        .enabled(cacheEnabled)
                        .maxSize(cacheMaxSize)
                        .watchInvalidation(watchInvalidation))
                .features(f -> f
                        .virtualThreads(virtualThreads)
                        .telemetry(true))
                .extend(e -> e.policies(PolicyRegistry.builder()
                        .defaultPolicy(ResourcePolicy.builder()
                                .circuitBreaker(CircuitBreakerPolicy.disabled())
                                .cache(CachePolicy.of(Duration.ofSeconds(30)))
                                .build())
                        .build()))
                .build();
    }
}
