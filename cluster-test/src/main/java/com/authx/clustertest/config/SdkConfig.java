package com.authx.clustertest.config;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.policy.CachePolicy;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ReadConsistency;
import com.authx.sdk.policy.ResourcePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class SdkConfig {

    @Bean(destroyMethod = "close")
    public AuthxClient authxClient(ClusterProps props) {
        var addrs = props.spicedb().targets().split(",");
        return AuthxClient.builder()
                .connection(c -> c
                        .targets(addrs)
                        .presharedKey(props.spicedb().presharedKey())
                        .requestTimeout(Duration.ofSeconds(30)))
                .cache(c -> c
                        .enabled(true)
                        .maxSize(200_000)
                        .watchInvalidation(true))
                .features(f -> f
                        .virtualThreads(true)
                        .telemetry(true)
                        .shutdownHook(false))
                .extend(e -> e.policies(PolicyRegistry.builder()
                        .defaultPolicy(ResourcePolicy.builder()
                                .cache(CachePolicy.of(Duration.ofSeconds(30)))
                                .readConsistency(ReadConsistency.session())
                                .build())
                        .build()))
                .build();
    }
}
