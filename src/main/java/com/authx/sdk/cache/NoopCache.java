package com.authx.sdk.cache;

import java.util.Optional;
import java.util.function.Predicate;

/** No-op cache — all operations are no-ops. Thread-safe singleton. */
public enum NoopCache implements Cache<Object, Object> {
    INSTANCE;

    @Override public Optional<Object> get(Object key) { return Optional.empty(); }
    @Override public Object getIfPresent(Object key) { return null; }
    @Override public void put(Object key, Object value) {}
    @Override public void invalidate(Object key) {}
    @Override public void invalidateAll(Predicate<Object> filter) {}
    @Override public void invalidateAll() {}
    @Override public long size() { return 0; }
    @Override public CacheStats stats() { return CacheStats.EMPTY; }
}
