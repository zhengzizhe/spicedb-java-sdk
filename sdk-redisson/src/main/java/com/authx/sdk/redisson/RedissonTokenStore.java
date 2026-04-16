package com.authx.sdk.redisson;

import com.authx.sdk.spi.DistributedTokenStore;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Redisson-backed {@link DistributedTokenStore} for cross-JVM SESSION consistency.
 *
 * <p>Stores tokens as Redis strings under {@code keyPrefix + key} with TTL. The
 * caller owns the {@link RedissonClient} lifecycle (creates and shuts it down).
 *
 * <p>Honors the SPI contract: never throws on Redis errors. Failures are logged
 * at {@link Level#WARNING} and swallowed; {@link #get} returns {@code null} on miss
 * or error.
 *
 * <pre>
 * RedissonClient redis = Redisson.create(config);
 * DistributedTokenStore store = new RedissonTokenStore(
 *     redis, Duration.ofSeconds(60), "authx:token:");
 *
 * AuthxClient client = AuthxClient.builder()
 *     .connection(c -&gt; c.targets("...").presharedKey("..."))
 *     .extend(e -&gt; e.components(SdkComponents.builder()
 *             .tokenStore(store)
 *             .build()))
 *     .build();
 * </pre>
 */
public final class RedissonTokenStore implements DistributedTokenStore {

    private static final Logger LOG = System.getLogger(RedissonTokenStore.class.getName());

    private final RedissonClient client;
    private final long ttlMs;
    private final String prefix;

    public RedissonTokenStore(RedissonClient client, Duration ttl, String keyPrefix) {
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        this.ttlMs = ttl.toMillis();
        this.prefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
    }

    @Override
    public void set(String key, String token) {
        try {
            client.<String>getBucket(prefix + key, StringCodec.INSTANCE)
                  .set(token, ttlMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    () -> "RedissonTokenStore set failed for key " + key, e);
        }
    }

    @Override
    public @Nullable String get(String key) {
        try {
            return client.<String>getBucket(prefix + key, StringCodec.INSTANCE).get();
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    () -> "RedissonTokenStore get failed for key " + key, e);
            return null;
        }
    }
}
