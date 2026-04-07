package com.authx.sdk.stress;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.policy.*;
import org.junit.jupiter.api.*;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests that verify:
 * 1. Metrics recording works without telemetry enabled
 * 2. Cold-start L2: Instance B (empty L1) hits Redis populated by Instance A
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ColdStartL2Test {

    static final String SPICEDB = "localhost:50061";
    static final String KEY = "testkey";

    static Object redisClient;

    @BeforeAll
    static void checkInfra() {
        try {
            var c = AuthxClient.builder().connection(cc -> cc.target(SPICEDB).presharedKey(KEY)).build();
            c.close();
        } catch (Exception e) {
            assumeTrue(false, "SpiceDB not reachable: " + e.getMessage());
        }
        try {
            var rc = io.lettuce.core.RedisClient.create("redis://localhost:6379");
            rc.connect().sync().ping();
            redisClient = rc;
        } catch (Exception e) {
            assumeTrue(false, "Redis not reachable: " + e.getMessage());
        }
    }

    @AfterAll
    static void cleanup() {
        if (redisClient instanceof io.lettuce.core.RedisClient rc) rc.shutdown();
    }

    @Test
    @Order(1)
    void metricsRecordWithoutTelemetry() {
        // telemetry=false (default), but metrics should still record
        var client = AuthxClient.builder()
                .connection(c -> c.target(SPICEDB).presharedKey(KEY))
                .cache(c -> c.enabled(true))
                .build();

        var docId = "metrics-test-" + System.nanoTime();
        client.on("document").grant(docId, "editor", "metrics-user");
        client.on("document").resource(docId)
                .check("edit").withConsistency(Consistency.full()).by("metrics-user").hasPermission();

        var snapshot = client.metrics().snapshot();
        System.out.printf("[Metrics] requests=%d, errors=%d, latency p50=%.2fms, p99=%.2fms%n",
                snapshot.totalRequests(), snapshot.totalErrors(),
                snapshot.latencyP50Ms(), snapshot.latencyP99Ms());

        assertThat(snapshot.totalRequests()).as("requests should be recorded without telemetry")
                .isGreaterThan(0);
        assertThat(snapshot.latencyP99Ms()).as("latency should be recorded")
                .isGreaterThan(0);

        client.close();
    }

    @Test
    @Order(2)
    void coldStartL2_hitRedisPopulatedByOtherInstance() throws Exception {
        // Flush Redis to ensure clean state
        ((io.lettuce.core.RedisClient) redisClient).connect().sync().flushall();

        var policies = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .cache(CachePolicy.ofTtl(Duration.ofSeconds(30)))
                        .readConsistency(ReadConsistency.minimizeLatency())
                        .build())
                .build();

        // Instance A: write + populate L1 + L2 Redis
        var instanceA = AuthxClient.builder()
                .connection(c -> c.target(SPICEDB).presharedKey(KEY))
                .cache(c -> c.enabled(true).maxSize(50_000)
                        .redis(redisClient).redisTtl(Duration.ofSeconds(30)))
                .extend(e -> e.policies(policies))
                .build();

        var docId = "l2-cold-" + System.nanoTime();
        var grantResult = instanceA.on("document").grant(docId, "editor", "l2-cold-user");
        // Read with the write token to guarantee fresh data, then re-read with minimizeLatency to populate cache
        boolean freshCheck = instanceA.on("document").resource(docId)
                .check("edit").withConsistency(grantResult.asConsistency()).by("l2-cold-user").hasPermission();
        assertThat(freshCheck).as("fresh check after grant should be true").isTrue();
        // Wait for SpiceDB CockroachDB quantization window (~5s) so minimizeLatency reads see the grant
        Thread.sleep(6000);
        instanceA.on("document").resource(docId)
                .check("edit").by("l2-cold-user").hasPermission();

        // Verify Redis has the entry
        var redis = ((io.lettuce.core.RedisClient) redisClient).connect().sync();
        String redisKey = "authx:check:document:" + docId;
        var redisValue = redis.hget(redisKey, "edit:user:l2-cold-user");
        System.out.println("[L2] Redis value for " + redisKey + ": " + redisValue);
        assertThat(redisValue).as("Redis should have cached entry from Instance A").isNotNull();
        assertThat(redisValue).contains("HAS_PERMISSION");

        // Instance B: cold start (empty L1), should hit Redis L2
        var instanceB = AuthxClient.builder()
                .connection(c -> c.target("localhost:50062").presharedKey(KEY))
                .cache(c -> c.enabled(true).maxSize(50_000)
                        .redis(redisClient).redisTtl(Duration.ofSeconds(30)))
                .extend(e -> e.policies(policies))
                .build();

        // First read from Instance B — L1 miss, should hit L2 Redis
        boolean canEdit = instanceB.on("document").resource(docId)
                .check("edit").by("l2-cold-user").hasPermission();
        assertThat(canEdit).as("Instance B should hit L2 Redis populated by Instance A").isTrue();

        var bMetrics = instanceB.metrics();
        System.out.printf("[L2] Instance B: cache hits=%d, misses=%d, hitRate=%.1f%%%n",
                bMetrics.cacheHits(), bMetrics.cacheMisses(), bMetrics.cacheHitRate() * 100);

        // Second read from Instance B — should hit L1 (promoted from L2)
        instanceB.on("document").resource(docId)
                .check("edit").by("l2-cold-user").hasPermission();
        System.out.printf("[L2] Instance B after 2nd read: cache hits=%d, misses=%d%n",
                bMetrics.cacheHits(), bMetrics.cacheMisses());

        instanceA.close();
        instanceB.close();
    }
}
