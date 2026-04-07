package com.authx.sdk.cache;

import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.enums.Permissionship;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Redis-backed {@link IndexedCache} for permission check results.
 *
 * <p>Storage layout — one Redis Hash per resource:
 * <ul>
 *   <li>Redis Key: {@code authx:check:{resourceType}:{resourceId}}</li>
 *   <li>Hash Field: {@code {permission}:{subjectType}:{subjectId}}</li>
 *   <li>Hash Value: {@code {permissionship}|{zedToken}|{expiresAt}} (pipe-delimited)</li>
 * </ul>
 *
 * <p>This layout enables O(1) single-entry lookups via {@code HGET} and O(k)
 * per-resource invalidation via {@code DEL} on the entire hash.
 */
public class RedisCacheAdapter implements IndexedCache<CheckKey, CheckResult> {

    private static final String KEY_PREFIX = "authx:check:";
    private final RedisCommands<String, String> commands;
    private final long ttlSeconds;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public RedisCacheAdapter(RedisCommands<String, String> commands, long ttlSeconds) {
        this.commands = commands;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public Optional<CheckResult> get(CheckKey key) {
        return Optional.ofNullable(getIfPresent(key));
    }

    @Override
    public CheckResult getIfPresent(CheckKey key) {
        String raw = commands.hget(redisKey(key), field(key));
        if (raw == null) {
            misses.increment();
            return null;
        }
        hits.increment();
        return deserialize(raw);
    }

    @Override
    public void put(CheckKey key, CheckResult value) {
        String rk = redisKey(key);
        commands.hset(rk, field(key), serialize(value));
        commands.expire(rk, ttlSeconds);
    }

    @Override
    public CheckResult getOrLoad(CheckKey key, Function<CheckKey, CheckResult> loader) {
        CheckResult v = getIfPresent(key);
        if (v != null) return v;
        v = loader.apply(key);
        if (v != null) put(key, v);
        return v;
    }

    @Override
    public void invalidate(CheckKey key) {
        commands.hdel(redisKey(key), field(key));
    }

    @Override
    public void invalidateByIndex(String indexKey) {
        commands.del(KEY_PREFIX + indexKey);
    }

    @Override
    public void invalidateAll(Predicate<CheckKey> filter) {
        throw new UnsupportedOperationException(
                "Predicate-based invalidation not supported for Redis. Use invalidateByIndex().");
    }

    @Override
    public void invalidateAll() {
        // No-op: full Redis flush is dangerous in shared environments
    }

    @Override
    public long size() {
        return -1;
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(hits.sum(), misses.sum(), 0);
    }

    // ---- internal helpers ----

    /** Redis key: {@code authx:check:{resourceType}:{resourceId}} */
    private String redisKey(CheckKey key) {
        return KEY_PREFIX + key.resourceIndex();
    }

    /** Hash field: {@code {permission}:{subjectType}:{subjectId}} */
    private String field(CheckKey key) {
        return key.permission().name() + ":" + key.subject().type() + ":" + key.subject().id();
    }

    private String serialize(CheckResult r) {
        String exp = r.expiresAt().map(Instant::toString).orElse("");
        return r.permissionship().name() + "|" + (r.zedToken() != null ? r.zedToken() : "") + "|" + exp;
    }

    private CheckResult deserialize(String raw) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 2) return null;
        var perm = Permissionship.valueOf(parts[0]);
        var token = parts[1].isEmpty() ? null : parts[1];
        var expiresAt = parts.length > 2 && !parts[2].isEmpty()
                ? Optional.of(Instant.parse(parts[2])) : Optional.<Instant>empty();
        return new CheckResult(perm, token, expiresAt);
    }
}
