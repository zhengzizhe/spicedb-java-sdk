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
        var tracker = new TokenTracker();
        tracker.recordWrite("document", "zed_token_1");

        assertThat(tracker.getLastWriteToken("document")).isEqualTo("zed_token_1");
    }

    @Test
    void recordWriteOverwritesPreviousToken() {
        var tracker = new TokenTracker();
        tracker.recordWrite("document", "zed_token_1");
        tracker.recordWrite("document", "zed_token_2");

        assertThat(tracker.getLastWriteToken("document")).isEqualTo("zed_token_2");
    }

    @Test
    void recordWriteIgnoresNullToken() {
        var tracker = new TokenTracker();
        tracker.recordWrite("document", null);

        assertThat(tracker.getLastWriteToken("document")).isNull();
    }

    @Test
    void recordWriteIgnoresNullResourceType() {
        var tracker = new TokenTracker();
        tracker.recordWrite(null, "zed_token_1");

        assertThat(tracker.getLastWriteToken(null)).isNull();
    }

    @Test
    void recordWriteWithoutResourceTypeUsesGlobal() {
        var tracker = new TokenTracker();
        tracker.recordWrite("zed_token_global");

        assertThat(tracker.getLastWriteToken()).isNotNull();
    }

    @Test
    void getLastWriteTokenReturnsNullWhenEmpty() {
        var tracker = new TokenTracker();
        assertThat(tracker.getLastWriteToken()).isNull();
        assertThat(tracker.getLastWriteToken("document")).isNull();
    }

    @Test
    void getLastWriteTokenWithNullFallsToGlobal() {
        var tracker = new TokenTracker();
        tracker.recordWrite("zed_token_1");

        assertThat(tracker.getLastWriteToken(null)).isNotNull();
    }

    @Test
    void separateResourceTypesHaveIndependentTokens() {
        var tracker = new TokenTracker();
        tracker.recordWrite("document", "token_doc");
        tracker.recordWrite("folder", "token_folder");

        assertThat(tracker.getLastWriteToken("document")).isEqualTo("token_doc");
        assertThat(tracker.getLastWriteToken("folder")).isEqualTo("token_folder");
    }

    // ---- resolve ----

    @Test
    void resolveMinimizeLatencyReturnsMinimizeLatency() {
        var tracker = new TokenTracker();
        var result = tracker.resolve(ReadConsistency.minimizeLatency(), "document");

        assertThat(result).isInstanceOf(Consistency.MinimizeLatency.class);
    }

    @Test
    void resolveSessionWithNoTokenReturnsMinimizeLatency() {
        var tracker = new TokenTracker();
        var result = tracker.resolve(ReadConsistency.session(), "document");

        assertThat(result).isInstanceOf(Consistency.MinimizeLatency.class);
    }

    @Test
    void resolveSessionWithTokenReturnsAtLeast() {
        var tracker = new TokenTracker();
        tracker.recordWrite("document", "zed_token_42");

        var result = tracker.resolve(ReadConsistency.session(), "document");

        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
        assertThat(((Consistency.AtLeast) result).zedToken()).isEqualTo("zed_token_42");
    }

    @Test
    void resolveStrongReturnsFull() {
        var tracker = new TokenTracker();
        var result = tracker.resolve(ReadConsistency.strong(), "document");

        assertThat(result).isInstanceOf(Consistency.Full.class);
    }

    @Test
    void resolveSnapshotReturnsFull() {
        var tracker = new TokenTracker();
        var result = tracker.resolve(ReadConsistency.snapshot(), "document");

        assertThat(result).isInstanceOf(Consistency.Full.class);
    }

    @Test
    void resolveBoundedStalenessReturnsMinimizeLatency() {
        var tracker = new TokenTracker();
        var result = tracker.resolve(
                ReadConsistency.boundedStaleness(java.time.Duration.ofSeconds(5)), "document");

        assertThat(result).isInstanceOf(Consistency.MinimizeLatency.class);
    }

    @Test
    void resolveWithoutResourceTypeUsesGlobal() {
        var tracker = new TokenTracker();
        tracker.recordWrite("zed_token_global");

        var result = tracker.resolve(ReadConsistency.session());

        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
    }

    // ---- Distributed token store ----

    @Test
    void distributedStoreIsUsedForWrite() {
        var store = new InMemoryDistributedStore();
        var tracker = new TokenTracker(store);

        tracker.recordWrite("document", "zed_token_distributed");

        assertThat(store.get("last_write:document")).isEqualTo("zed_token_distributed");
    }

    @Test
    void distributedStoreIsConsultedForResolve() {
        var store = new InMemoryDistributedStore();
        store.set("last_write:document", "zed_token_remote");

        var tracker = new TokenTracker(store);
        var result = tracker.resolve(ReadConsistency.session(), "document");

        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
        assertThat(((Consistency.AtLeast) result).zedToken()).isEqualTo("zed_token_remote");
    }

    @Test
    void distributedStoreFailureDegradesToLocal() {
        var failingStore = new DistributedTokenStore() {
            @Override
            public void set(String key, String token) {
                throw new RuntimeException("Redis down");
            }

            @Override
            public String get(String key) {
                throw new RuntimeException("Redis down");
            }
        };

        var tracker = new TokenTracker(failingStore);

        // Should not throw
        tracker.recordWrite("document", "zed_token_local");

        // Local token should still be available
        assertThat(tracker.getLastWriteToken("document")).isEqualTo("zed_token_local");

        // Resolve should use local token
        var result = tracker.resolve(ReadConsistency.session(), "document");
        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
    }

    @Test
    void distributedStorePreferredOverLocal() {
        // Simulate: another instance wrote a token to the distributed store
        // but the local tracker has not seen that write.
        var store = new InMemoryDistributedStore();
        store.set("last_write:document", "zed_token_remote");

        var tracker = new TokenTracker(store);
        // Do NOT call recordWrite — so local map is empty, only distributed has a token

        var result = tracker.resolve(ReadConsistency.session(), "document");
        // Distributed token is checked first, before local
        assertThat(result).isInstanceOf(Consistency.AtLeast.class);
        assertThat(((Consistency.AtLeast) result).zedToken()).isEqualTo("zed_token_remote");
    }

    @Test
    void recordReadIsNoOp() {
        // recordRead is reserved for future use, just ensure it doesn't throw
        var tracker = new TokenTracker();
        tracker.recordRead("some_token");
        // No assertion needed — just verifying no exception
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
