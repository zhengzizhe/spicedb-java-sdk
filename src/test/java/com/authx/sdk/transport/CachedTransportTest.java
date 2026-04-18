package com.authx.sdk.transport;

import com.authx.sdk.cache.CaffeineCache;
import com.authx.sdk.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CachedTransport with InMemoryTransport as delegate.
 */
class CachedTransportTest {

    private InMemoryTransport inner;
    private CachedTransport cached;
    private CaffeineCache<CheckKey, CheckResult> cache;

    @BeforeEach
    void setup() {
        inner = new InMemoryTransport();
        cache = new CaffeineCache<>(1000, Duration.ofSeconds(10), CheckKey::resourceIndex);
        cached = new CachedTransport(inner, cache);
    }

    @Test
    void check_cachesOnMinimizeLatency() {
        // Write a relationship
        cached.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));

        // First check — miss, goes to delegate
        var r1 = cached.check(CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));
        assertTrue(r1.hasPermission());
        assertEquals(1, cache.size());

        // Second check — cache hit
        var r2 = cached.check(CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));
        assertTrue(r2.hasPermission());
    }

    @Test
    void check_fullConsistency_bypassesCache() {
        cached.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));

        // Full consistency — should NOT cache
        cached.check(CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.full()));
        assertEquals(0, cache.size());
    }

    @Test
    void write_invalidatesCache() {
        // Populate cache
        cached.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));
        cached.check(CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));
        assertEquals(1, cache.size());

        // Write (grant another user) → should invalidate d1's cache
        cached.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d1"),
                Relation.of("viewer"),
                SubjectRef.of("user", "bob", null))));
        assertEquals(0, cache.size());
    }

    @Test
    void delete_invalidatesCache() {
        cached.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));
        cached.check(CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        cached.deleteRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.DELETE,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));
        assertEquals(0, cache.size());
    }

    /**
     * Regression test for the write-vs-read race window: a concurrent reader
     * that lands between pre-invalidate and write completion can populate the
     * cache with stale data. The double-delete pattern (post-invalidate after
     * write returns) closes that window.
     *
     * <p>We simulate the race deterministically by wrapping the inner transport
     * in a delegate that calls back into the cache mid-write — exactly what a
     * concurrent reader would do.
     */
    @Test
    void write_doubleDelete_purgesEntryPopulatedDuringWrite() {
        // Inject a delegate that, during writeRelationships(), populates the
        // cache for the same key — modeling a concurrent reader's cache.put().
        var staleResult = new CheckResult(
                com.authx.sdk.model.enums.Permissionship.NO_PERMISSION, null, java.util.Optional.empty());
        var staleKey = new CheckKey(
                ResourceRef.of("document", "d1"),
                Permission.of("editor"),
                SubjectRef.of("user", "alice", null));

        var raceyDelegate = new ForwardingTransport() {
            @Override protected SdkTransport delegate() { return inner; }
            @Override public GrantResult writeRelationships(List<SdkTransport.RelationshipUpdate> updates) {
                cache.put(staleKey, staleResult);   // simulates concurrent read populating cache
                return inner.writeRelationships(updates);
            }
        };
        var racyCached = new CachedTransport(raceyDelegate, cache);

        racyCached.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));

        // Without double-delete this would still contain the stale NO_PERMISSION
        // entry. With the post-invalidate, the cache must be empty for d1.
        assertEquals(0, cache.size(),
                "post-invalidate must purge entries populated during the write window");
    }

    @Test
    void write_doubleDelete_purgesEvenIfWriteThrows() {
        // If the delegate write throws (e.g. SpiceDB returned an error after
        // partially persisting), we still want to purge so the next read sees
        // SpiceDB's actual current state, not whatever we cached pre-write.
        var staleResult = new CheckResult(
                com.authx.sdk.model.enums.Permissionship.HAS_PERMISSION, null, java.util.Optional.empty());
        var staleKey = new CheckKey(
                ResourceRef.of("document", "d1"),
                Permission.of("editor"),
                SubjectRef.of("user", "alice", null));

        var failingDelegate = new ForwardingTransport() {
            @Override protected SdkTransport delegate() { return inner; }
            @Override public GrantResult writeRelationships(List<SdkTransport.RelationshipUpdate> updates) {
                cache.put(staleKey, staleResult);
                throw new RuntimeException("simulated SpiceDB failure mid-write");
            }
        };
        var racyCached = new CachedTransport(failingDelegate, cache);

        try {
            racyCached.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                    SdkTransport.RelationshipUpdate.Operation.TOUCH,
                    ResourceRef.of("document", "d1"),
                    Relation.of("editor"),
                    SubjectRef.of("user", "alice", null))));
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            // good
        }

        assertEquals(0, cache.size(),
                "post-invalidate in finally must run even when write throws");
    }
}
