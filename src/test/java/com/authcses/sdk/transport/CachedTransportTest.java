package com.authcses.sdk.transport;

import com.authcses.sdk.cache.CaffeineCheckCache;
import com.authcses.sdk.model.*;
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
    private CaffeineCheckCache cache;

    @BeforeEach
    void setup() {
        inner = new InMemoryTransport();
        cache = new CaffeineCheckCache(Duration.ofSeconds(10), 1000);
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
        var r1 = cached.check(CheckRequest.from("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));
        assertTrue(r1.hasPermission());
        assertEquals(1, cache.size());

        // Second check — cache hit
        var r2 = cached.check(CheckRequest.from("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));
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
        cached.check(CheckRequest.from("document", "d1", "editor", "user", "alice", Consistency.full()));
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
        cached.check(CheckRequest.from("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));
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
        cached.check(CheckRequest.from("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        cached.deleteRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.DELETE,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));
        assertEquals(0, cache.size());
    }
}
