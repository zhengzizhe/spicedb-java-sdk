package com.authcses.testapp;

import com.authcses.sdk.AuthCsesClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    /** Primary SDK client — connects to spicedb-1. */
    @Bean(destroyMethod = "close")
    @org.springframework.context.annotation.Primary
    public AuthCsesClient primaryClient() {
        return AuthCsesClient.builder()
                .target(target)
                .presharedKey(presharedKey)
                .cacheEnabled(cacheEnabled)
                .cacheMaxSize(cacheMaxSize)
                .watchInvalidation(watchInvalidation)
                .useVirtualThreads(virtualThreads)
                .build();
    }

    /** Secondary SDK client — connects to spicedb-2 (for multi-instance testing). */
    @Bean(name = "secondaryClient", destroyMethod = "close")
    public AuthCsesClient secondaryClient() {
        return AuthCsesClient.builder()
                .target(targetSecondary)
                .presharedKey(presharedKey)
                .cacheEnabled(cacheEnabled)
                .cacheMaxSize(cacheMaxSize)
                .watchInvalidation(watchInvalidation)
                .useVirtualThreads(virtualThreads)
                .build();
    }
}
