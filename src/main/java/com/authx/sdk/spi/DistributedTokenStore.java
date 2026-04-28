package com.authx.sdk.spi;

import org.jspecify.annotations.Nullable;

/**
 * SPI for sharing ZedTokens across SDK instances.
 *
 * <p>Used by {@link com.authx.sdk.transport.TokenTracker} to enable
 * cross-instance SESSION consistency. Without this, SESSION consistency
 * only works within a single JVM.
 *
 * <p>Implementation contract:
 * <ul>
 *   <li>{@link #set} must be best-effort (never throw)</li>
 *   <li>{@link #get} returns null on miss or error</li>
 *   <li>Values should have a TTL (recommended: 60s)</li>
 * </ul>
 *
 * <pre>
 * // Shared-storage example. The SDK does not provide a concrete implementation.
 * DistributedTokenStore store = new DistributedTokenStore() {
 *     public void set(String key, String token) {
 *         sharedStorage.setWithTtl("authx:token:" + key, token, Duration.ofSeconds(60));
 *     }
 *     public String get(String key) {
 *         return sharedStorage.get("authx:token:" + key);
 *     }
 * };
 * </pre>
 */
public interface DistributedTokenStore {

    /**
     * Store a token. Must not throw — log and swallow on error.
     */
    void set(String key, String token);

    /**
     * Retrieve a token. Returns null on miss or error.
     */
    @Nullable String get(String key);
}
