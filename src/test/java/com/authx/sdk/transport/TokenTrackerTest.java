package com.authx.sdk.transport;

import com.authx.sdk.model.Consistency;
import com.authx.sdk.policy.ReadConsistency;
import com.authx.sdk.spi.DistributedTokenStore;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link TokenTracker} — ZedToken tracking for SESSION consistency.
 */
class TokenTrackerTest {

    // ---- recordWrite / getLastWriteToken ----

    @Test
    void recordWriteStoresTokenByResourceType() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("document", "zed_token_1");

        assertThat(tracker.getLastWriteToken("document")).isEqualTo("zed_token_1");
    }

    @Test
    void recordWriteOverwritesPreviousToken() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("document", "zed_token_1");
        tracker.recordWrite("document", "zed_token_2");

        assertThat(tracker.getLastWriteToken("document")).isEqualTo("zed_token_2");
    }

    @Test
    void recordWriteIgnoresNullToken() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("document", null);

        assertThat(tracker.getLastWriteToken("document")).isNull();
    }

    @Test
    void recordWriteIgnoresNullResourceType() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        tracker.recordWrite(null, "zed_token_1");

        assertThat(tracker.getLastWriteToken(null)).isNull();
    }

    @Test
    void recordWriteWithoutResourceTypeUsesGlobal() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("zed_token_global");

        assertThat(tracker.getLastWriteToken()).isNotNull();
    }

    @Test
    void getLastWriteTokenReturnsNullWhenEmpty() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        assertThat(tracker.getLastWriteToken()).isNull();
        assertThat(tracker.getLastWriteToken("document")).isNull();
    }

    @Test
    void getLastWriteTokenWithNullFallsToGlobal() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("zed_token_1");

        assertThat(tracker.getLastWriteToken(null)).isNotNull();
    }

    @Test
    void separateResourceTypesHaveIndependentTokens() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("document", "token_doc");
        tracker.recordWrite("folder", "token_folder");

        assertThat(tracker.getLastWriteToken("document")).isEqualTo("token_doc");
        assertThat(tracker.getLastWriteToken("folder")).isEqualTo("token_folder");
    }

    // ---- resolve ----

    @Test
    void resolveMinimizeLatencyReturnsMinimizeLatency() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        com.authx.sdk.model.Consistency result = tracker.resolve(ReadConsistency.minimizeLatency(), "document");

        assertThat(result).isInstanceOf(Consistency.MinimizeLatency.class);
    }

    @Test
    void resolveSessionWithNoTokenReturnsMinimizeLatency() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        com.authx.sdk.model.Consistency result = tracker.resolve(ReadConsistency.session(), "document");

        assertThat(result).isInstanceOf(Consistency.MinimizeLatency.class);
    }

    @Test
    void resolveSessionWithTokenReturnsAtLeast() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("document", "zed_token_42");

        com.authx.sdk.model.Consistency result = tracker.resolve(ReadConsistency.session(), "document");

        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
        assertThat(((Consistency.AtLeast) result).zedToken()).isEqualTo("zed_token_42");
    }

    @Test
    void resolveStrongReturnsFull() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        com.authx.sdk.model.Consistency result = tracker.resolve(ReadConsistency.strong(), "document");

        assertThat(result).isInstanceOf(Consistency.Full.class);
    }

    @Test
    void resolveSnapshotReturnsFull() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        com.authx.sdk.model.Consistency result = tracker.resolve(ReadConsistency.snapshot(), "document");

        assertThat(result).isInstanceOf(Consistency.Full.class);
    }

    @Test
    void resolveBoundedStalenessReturnsMinimizeLatency() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        com.authx.sdk.model.Consistency result = tracker.resolve(
                ReadConsistency.boundedStaleness(java.time.Duration.ofSeconds(5)), "document");

        assertThat(result).isInstanceOf(Consistency.MinimizeLatency.class);
    }

    @Test
    void resolveWithoutResourceTypeUsesGlobal() {
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("zed_token_global");

        com.authx.sdk.model.Consistency result = tracker.resolve(ReadConsistency.session());

        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
    }

    // ---- Distributed token store ----

    @Test
    void distributedStoreIsUsedForWrite() {
        com.authx.sdk.transport.TokenTrackerTest.InMemoryDistributedStore store = new InMemoryDistributedStore();
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker(store);

        tracker.recordWrite("document", "zed_token_distributed");

        assertThat(store.get("last_write:document")).isEqualTo("zed_token_distributed");
    }

    @Test
    void distributedStoreIsConsultedForResolve() {
        com.authx.sdk.transport.TokenTrackerTest.InMemoryDistributedStore store = new InMemoryDistributedStore();
        store.set("last_write:document", "zed_token_remote");

        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker(store);
        com.authx.sdk.model.Consistency result = tracker.resolve(ReadConsistency.session(), "document");

        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
        assertThat(((Consistency.AtLeast) result).zedToken()).isEqualTo("zed_token_remote");
    }

    @Test
    void distributedStoreFailureDegradesToLocal() {
        DistributedTokenStore failingStore = new DistributedTokenStore() {
            @Override
            public void set(String key, String token) {
                throw new RuntimeException("Redis down");
            }

            @Override
            public String get(String key) {
                throw new RuntimeException("Redis down");
            }
        };

        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker(failingStore);

        // Should not throw
        tracker.recordWrite("document", "zed_token_local");

        // Local token should still be available
        assertThat(tracker.getLastWriteToken("document")).isEqualTo("zed_token_local");

        // Resolve should use local token
        com.authx.sdk.model.Consistency result = tracker.resolve(ReadConsistency.session(), "document");
        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
    }

    @Test
    void distributedStorePreferredOverLocal() {
        // Simulate: another instance wrote a token to the distributed store
        // but the local tracker has not seen that write.
        com.authx.sdk.transport.TokenTrackerTest.InMemoryDistributedStore store = new InMemoryDistributedStore();
        store.set("last_write:document", "zed_token_remote");

        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker(store);
        // Do NOT call recordWrite — so local map is empty, only distributed has a token

        com.authx.sdk.model.Consistency result = tracker.resolve(ReadConsistency.session(), "document");
        // Distributed token is checked first, before local
        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
        assertThat(((Consistency.AtLeast) result).zedToken()).isEqualTo("zed_token_remote");
    }

    @Test
    void recordReadIsNoOp() {
        // recordRead is reserved for future use, just ensure it doesn't throw
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker();
        tracker.recordRead("some_token");
        // No assertion needed — just verifying no exception
    }

    // ---- Observability: events + counter on degradation ----

    @Test
    void distributedStoreFailure_publishesUnavailableEvent_once() {
        com.authx.sdk.event.DefaultTypedEventBus bus = new com.authx.sdk.event.DefaultTypedEventBus(Runnable::run);
        java.util.concurrent.atomic.AtomicInteger unavailableEvents = new java.util.concurrent.atomic.AtomicInteger(0);
        bus.subscribe(com.authx.sdk.event.SdkTypedEvent.TokenStoreUnavailable.class, e -> unavailableEvents.incrementAndGet());

        DistributedTokenStore alwaysFailing = new DistributedTokenStore() {
            @Override public void set(String key, String token) { throw new RuntimeException("redis down"); }
            @Override public String get(String key) { throw new RuntimeException("redis down"); }
        };
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker(alwaysFailing);
        tracker.setEventBus(bus);

        // Multiple failures, but only ONE event (state transition).
        tracker.recordWrite("doc", "tok-1");
        tracker.recordWrite("doc", "tok-2");
        tracker.recordWrite("doc", "tok-3");

        assertThat(unavailableEvents.get())
                .as("Unavailable event must be emitted exactly once on first failure")
                .isEqualTo(1);
        // Counter accumulates ALL failures so dashboards see sustained outage.
        assertThat(tracker.distributedFailureCount()).isGreaterThanOrEqualTo(3);
        assertThat(tracker.isDistributedAvailable()).isFalse();
    }

    @Test
    void distributedStoreRecovery_publishesRecoveredEvent() {
        com.authx.sdk.event.DefaultTypedEventBus bus = new com.authx.sdk.event.DefaultTypedEventBus(Runnable::run);
        java.util.concurrent.atomic.AtomicInteger unavailableEvents = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger recoveredEvents = new java.util.concurrent.atomic.AtomicInteger(0);
        bus.subscribe(com.authx.sdk.event.SdkTypedEvent.TokenStoreUnavailable.class, e -> unavailableEvents.incrementAndGet());
        bus.subscribe(com.authx.sdk.event.SdkTypedEvent.TokenStoreRecovered.class, e -> recoveredEvents.incrementAndGet());

        // Toggle: starts working, then fails, then recovers
        java.util.concurrent.atomic.AtomicBoolean failingFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        DistributedTokenStore store = new DistributedTokenStore() {
            private final java.util.concurrent.ConcurrentHashMap<String, String> data = new java.util.concurrent.ConcurrentHashMap<>();
            @Override public void set(String key, String token) {
                if (failingFlag.get()) throw new RuntimeException("redis down");
                data.put(key, token);
            }
            @Override public String get(String key) {
                if (failingFlag.get()) throw new RuntimeException("redis down");
                return data.get(key);
            }
        };
        com.authx.sdk.transport.TokenTracker tracker = new TokenTracker(store);
        tracker.setEventBus(bus);

        tracker.recordWrite("doc", "tok-1");           // OK, no events
        failingFlag.set(true);
        tracker.recordWrite("doc", "tok-2");           // → Unavailable event
        tracker.recordWrite("doc", "tok-3");           // still failing, no extra event
        failingFlag.set(false);
        tracker.recordWrite("doc", "tok-4");           // → Recovered event

        assertThat(unavailableEvents.get()).as("one Unavailable event").isEqualTo(1);
        assertThat(recoveredEvents.get()).as("one Recovered event").isEqualTo(1);
        assertThat(tracker.isDistributedAvailable()).isTrue();
    }

    // ---- Helper ----

    private static class InMemoryDistributedStore implements DistributedTokenStore {
        private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

        @Override
        public void set(String key, String token) {
            store.put(key, token);
        }

        @Override
        public String get(String key) {
            return store.get(key);
        }
    }
}
