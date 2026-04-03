package com.authcses.sdk.cache;

import com.authcses.sdk.model.CheckResult;

import java.util.Optional;

/**
 * No-op cache — always misses. Used when caching is disabled.
 */
enum NoopCheckCache implements CheckCache {
    INSTANCE;

    @Override public Optional<CheckResult> get(String rt, String ri, String p, String st, String si) { return Optional.empty(); }
    @Override public void put(String rt, String ri, String p, String st, String si, CheckResult r) {}
    @Override public void invalidateResource(String rt, String ri) {}
    @Override public void invalidateAll() {}
    @Override public long size() { return 0; }
}
