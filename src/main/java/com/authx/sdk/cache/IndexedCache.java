package com.authx.sdk.cache;

/**
 * Cache with secondary index for O(k) invalidation by index key.
 * Used for invalidating all entries belonging to a specific resource.
 *
 * Example: invalidateByIndex("document:doc-1") removes all CheckKey entries
 * where CheckKey.resourceIndex() equals "document:doc-1".
 */
public interface IndexedCache<K, V> extends Cache<K, V> {

    /** Invalidate all entries whose index key matches. O(k) where k = entries for this index key. */
    void invalidateByIndex(String indexKey);
}
