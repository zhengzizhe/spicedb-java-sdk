package com.authx.sdk.spi;

/**
 * SPI for sharing ZedTokens across SDK instances (e.g., via Redis).
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
 * // Redis example
 * DistributedTokenStore store = new DistributedTokenStore() {
 *     public void set(String key, String token) {
 *         redis.setex("authx:token:" + key, 60, token);
 *     }
 *     public String get(String key) {
 *         return redis.get("authx:token:" + key);
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
    String get(String key);
}
