package com.authx.sdk.transport;

import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.event.SdkTypedEvent;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.policy.ReadConsistency;
import com.authx.sdk.spi.DistributedTokenStore;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link TokenTracker} — ZedToken tracking for SESSION consistency.
 */
class TokenTrackerTest {

    // ---- recordWrite / getLastWriteToken ----

    @Test
    void recordWriteStoresTokenByResourceType() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("document", "zed_token_1");

        assertThat(tracker.getLastWriteToken("document")).isEqualTo("zed_token_1");
    }

    @Test
    void recordWriteOverwritesPreviousToken() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("document", "zed_token_1");
        tracker.recordWrite("document", "zed_token_2");

        assertThat(tracker.getLastWriteToken("document")).isEqualTo("zed_token_2");
    }

    @Test
    void recordWriteIgnoresNullToken() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("document", null);

        assertThat(tracker.getLastWriteToken("document")).isNull();
    }

    @Test
    void recordWriteIgnoresNullResourceType() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordWrite(null, "zed_token_1");

        assertThat(tracker.getLastWriteToken(null)).isNull();
    }

    @Test
    void recordWriteWithoutResourceTypeUsesGlobal() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("zed_token_global");

        assertThat(tracker.getLastWriteToken()).isNotNull();
    }

    @Test
    void getLastWriteTokenReturnsNullWhenEmpty() {
        TokenTracker tracker = new TokenTracker();
        assertThat(tracker.getLastWriteToken()).isNull();
        assertThat(tracker.getLastWriteToken("document")).isNull();
    }

    @Test
    void getLastWriteTokenWithNullFallsToGlobal() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("zed_token_1");

        assertThat(tracker.getLastWriteToken(null)).isNotNull();
    }

    @Test
    void separateResourceTypesHaveIndependentTokens() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("document", "token_doc");
        tracker.recordWrite("folder", "token_folder");

        assertThat(tracker.getLastWriteToken("document")).isEqualTo("token_doc");
        assertThat(tracker.getLastWriteToken("folder")).isEqualTo("token_folder");
    }

    // ---- resolve ----

    @Test
    void resolveMinimizeLatencyReturnsMinimizeLatency() {
        TokenTracker tracker = new TokenTracker();
        Consistency result = tracker.resolve(ReadConsistency.minimizeLatency(), "document");

        assertThat(result).isInstanceOf(Consistency.MinimizeLatency.class);
    }

    @Test
    void resolveSessionWithNoTokenReturnsMinimizeLatency() {
        TokenTracker tracker = new TokenTracker();
        Consistency result = tracker.resolve(ReadConsistency.session(), "document");

        assertThat(result).isInstanceOf(Consistency.MinimizeLatency.class);
    }

    @Test
    void resolveSessionWithTokenReturnsAtLeast() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("document", "zed_token_42");

        Consistency result = tracker.resolve(ReadConsistency.session(), "document");

        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
        assertThat(((Consistency.AtLeast) result).zedToken()).isEqualTo("zed_token_42");
    }

    @Test
    void resolveStrongReturnsFull() {
        TokenTracker tracker = new TokenTracker();
        Consistency result = tracker.resolve(ReadConsistency.strong(), "document");

        assertThat(result).isInstanceOf(Consistency.Full.class);
    }

    @Test
    void resolveSnapshotReturnsFull() {
        TokenTracker tracker = new TokenTracker();
        Consistency result = tracker.resolve(ReadConsistency.snapshot(), "document");

        assertThat(result).isInstanceOf(Consistency.Full.class);
    }

    @Test
    void resolveBoundedStalenessReturnsMinimizeLatency() {
        TokenTracker tracker = new TokenTracker();
        Consistency result = tracker.resolve(
                ReadConsistency.boundedStaleness(Duration.ofSeconds(5)), "document");

        assertThat(result).isInstanceOf(Consistency.MinimizeLatency.class);
    }

    @Test
    void resolveWithoutResourceTypeUsesGlobal() {
        TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("zed_token_global");

        Consistency result = tracker.resolve(ReadConsistency.session());

        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
    }

    // ---- Distributed token store ----

    @Test
    void distributedStoreIsUsedForWrite() {
        TokenTrackerTest.InMemoryDistributedStore store = new InMemoryDistributedStore();
        TokenTracker tracker = new TokenTracker(store);

        tracker.recordWrite("document", "zed_token_distributed");

        assertThat(store.get("last_write:document")).isEqualTo("zed_token_distributed");
    }

    @Test
    void distributedStoreIsConsultedForResolve() {
        TokenTrackerTest.InMemoryDistributedStore store = new InMemoryDistributedStore();
        store.set("last_write:document", "zed_token_remote");

        TokenTracker tracker = new TokenTracker(store);
        Consistency result = tracker.resolve(ReadConsistency.session(), "document");

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

        TokenTracker tracker = new TokenTracker(failingStore);

        // Should not throw
        tracker.recordWrite("document", "zed_token_local");

        // Local token should still be available
        assertThat(tracker.getLastWriteToken("document")).isEqualTo("zed_token_local");

        // Resolve should use local token
        Consistency result = tracker.resolve(ReadConsistency.session(), "document");
        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
    }

    @Test
    void distributedStorePreferredOverLocal() {
        // Simulate: another instance wrote a token to the distributed store
        // but the local tracker has not seen that write.
        TokenTrackerTest.InMemoryDistributedStore store = new InMemoryDistributedStore();
        store.set("last_write:document", "zed_token_remote");

        TokenTracker tracker = new TokenTracker(store);
        // Do NOT call recordWrite — so local map is empty, only distributed has a token

        Consistency result = tracker.resolve(ReadConsistency.session(), "document");
        // Distributed token is checked first, before local
        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
        assertThat(((Consistency.AtLeast) result).zedToken()).isEqualTo("zed_token_remote");
    }

    @Test
    void recordReadIsNoOp() {
        // recordRead is reserved for future use, just ensure it doesn't throw
        TokenTracker tracker = new TokenTracker();
        tracker.recordRead("some_token");
        // No assertion needed — just verifying no exception
    }

    // ---- Observability: events + counter on degradation ----

    @Test
    void distributedStoreFailure_publishesUnavailableEvent_once() {
        DefaultTypedEventBus bus = new DefaultTypedEventBus(Runnable::run);
        AtomicInteger unavailableEvents = new AtomicInteger(0);
        bus.subscribe(SdkTypedEvent.TokenStoreUnavailable.class, e -> unavailableEvents.incrementAndGet());

        DistributedTokenStore alwaysFailing = new DistributedTokenStore() {
            @Override public void set(String key, String token) { throw new RuntimeException("redis down"); }
            @Override public String get(String key) { throw new RuntimeException("redis down"); }
        };
        TokenTracker tracker = new TokenTracker(alwaysFailing);
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
        DefaultTypedEventBus bus = new DefaultTypedEventBus(Runnable::run);
        AtomicInteger unavailableEvents = new AtomicInteger(0);
        AtomicInteger recoveredEvents = new AtomicInteger(0);
        bus.subscribe(SdkTypedEvent.TokenStoreUnavailable.class, e -> unavailableEvents.incrementAndGet());
        bus.subscribe(SdkTypedEvent.TokenStoreRecovered.class, e -> recoveredEvents.incrementAndGet());

        // Toggle: starts working, then fails, then recovers
        AtomicBoolean failingFlag = new AtomicBoolean(false);
        DistributedTokenStore store = new DistributedTokenStore() {
            private final ConcurrentHashMap<String, String> data = new ConcurrentHashMap<>();
            @Override public void set(String key, String token) {
                if (failingFlag.get()) throw new RuntimeException("redis down");
                data.put(key, token);
            }
            @Override public String get(String key) {
                if (failingFlag.get()) throw new RuntimeException("redis down");
                return data.get(key);
            }
        };
        TokenTracker tracker = new TokenTracker(store);
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
