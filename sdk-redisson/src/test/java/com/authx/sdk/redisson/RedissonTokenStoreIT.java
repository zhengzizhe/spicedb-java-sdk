package com.authx.sdk.redisson;

import com.authx.sdk.spi.DistributedTokenStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedissonTokenStoreIT {

    private static GenericContainer<?> redis;
    private static RedissonClient client;

    @BeforeAll
    static void up() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redis.start();
        Config c = new Config();
        c.useSingleServer().setAddress("redis://" + redis.getHost() + ":" + redis.getFirstMappedPort());
        client = Redisson.create(c);
    }

    @AfterAll
    static void down() {
        if (client != null) client.shutdown();
        if (redis != null) redis.stop();
    }

    @Test
    void roundTrip_setThenGet_returnsValue() {
        DistributedTokenStore store = new RedissonTokenStore(client, Duration.ofSeconds(30), "t1:");
        store.set("k", "v");
        assertThat(store.get("k")).isEqualTo("v");
    }

    @Test
    void ttl_keyExpires() throws InterruptedException {
        DistributedTokenStore store = new RedissonTokenStore(client, Duration.ofSeconds(1), "t2:");
        store.set("k", "v");
        Thread.sleep(1500);
        assertThat(store.get("k")).isNull();
    }

    @Test
    void prefixIsolation_twoStoresDoNotShareKeys() {
        DistributedTokenStore a = new RedissonTokenStore(client, Duration.ofSeconds(30), "ta:");
        DistributedTokenStore b = new RedissonTokenStore(client, Duration.ofSeconds(30), "tb:");
        a.set("k", "from-a");
        b.set("k", "from-b");
        assertThat(a.get("k")).isEqualTo("from-a");
        assertThat(b.get("k")).isEqualTo("from-b");
    }

    @Test
    void missingKey_returnsNull() {
        DistributedTokenStore store = new RedissonTokenStore(client, Duration.ofSeconds(30), "t4:");
        assertThat(store.get("never-set")).isNull();
    }

    @Test
    void redisDown_setAndGetDoNotThrow() {
        // Start a separate container we can kill mid-test so the client connects
        // successfully first, then loses Redis. This exercises the SPI contract
        // ("never throw on Redis failure") more honestly than a bad-from-start config.
        GenericContainer<?> doomed = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        doomed.start();
        Config c = new Config();
        c.useSingleServer()
                .setAddress("redis://" + doomed.getHost() + ":" + doomed.getFirstMappedPort())
                .setRetryAttempts(0)
                .setConnectTimeout(500)
                .setTimeout(500);
        RedissonClient cl = Redisson.create(c);
        try {
            DistributedTokenStore store = new RedissonTokenStore(cl, Duration.ofSeconds(30), "t5:");
            // Confirm wiring is healthy before we kill Redis
            store.set("warmup", "v");
            assertThat(store.get("warmup")).isEqualTo("v");
            // Pull the rug out
            doomed.stop();
            // SPI contract: must not throw on either op; get returns null on error
            store.set("k", "v");
            assertThat(store.get("k")).isNull();
        } finally {
            cl.shutdown();
        }
    }
}
