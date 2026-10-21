package com.authx.sdk.transport;

import com.authx.sdk.exception.TokenStoreUnavailableException;
import com.authx.sdk.model.*;
import com.authx.sdk.policy.*;
import com.authx.sdk.spi.CoordinatedTokenStore;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CoordinatedSessionTest {
    /** Test-only coordinator; production providers must implement these guarantees across processes. */
    static final class SharedStore implements CoordinatedTokenStore {
        private String token;
        private boolean uncertain;
        boolean failPublication;
        @Override public synchronized WriteResult executeWrite(Supplier<WriteResult> mutation) {
            if (uncertain) throw new IllegalStateException("Reconciliation required");
            uncertain = true;
            WriteResult result = mutation.get();
            if (failPublication) throw new IllegalStateException("Store failed after graph mutation");
            if (result.zedToken() == null || result.zedToken().isBlank()) throw new IllegalStateException("Missing token");
            token = result.zedToken();
            uncertain = false;
            return result;
        }
        @Override public synchronized String readRequiredToken() {
            if (uncertain || token == null) throw new IllegalStateException("No safe published token");
            return token;
        }
    }

    @Test void requiredModeDoesNotDowngradeOnMissingOrUncertainState() {
        assertThatThrownBy(() -> new TokenTracker().resolve(ReadConsistency.coordinatedSession(), "resource"))
                .isInstanceOf(TokenStoreUnavailableException.class);
        SharedStore store = new SharedStore();
        TokenTracker tracker = new TokenTracker(null, store);
        assertThatThrownBy(() -> tracker.resolve(ReadConsistency.coordinatedSession(), "resource"))
                .isInstanceOf(TokenStoreUnavailableException.class);
        tracker.executeWrite(() -> new WriteResult("grant-token", 1));
        assertThat(tracker.resolve(ReadConsistency.coordinatedSession(), "resource"))
                .isEqualTo(Consistency.atLeast("grant-token"));
        store.failPublication = true;
        assertThatThrownBy(() -> tracker.executeWrite(() -> new WriteResult("revocation-token", 1)))
                .isInstanceOf(TokenStoreUnavailableException.class);
        assertThatThrownBy(() -> new TokenTracker(null, store).resolve(ReadConsistency.coordinatedSession(), "other_type"))
                .isInstanceOf(TokenStoreUnavailableException.class);
    }

    @Test void twoClientsCoordinateActualMutationsRatherThanSortingOpaqueTokens() throws Exception {
        SharedStore store = new SharedStore();
        TokenTracker a = new TokenTracker(null, store);
        TokenTracker b = new TokenTracker(null, store);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger order = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<WriteResult> first = pool.submit(() -> a.executeWrite(() -> {
                firstEntered.countDown(); AuthorizationConsistencyRegressionTest.await(releaseFirst);
                assertThat(order.incrementAndGet()).isEqualTo(1);
                return new WriteResult("z-first-opaque", 1);
            }));
            AuthorizationConsistencyRegressionTest.await(firstEntered);
            Future<WriteResult> second = pool.submit(() -> b.executeWrite(() -> {
                assertThat(order.incrementAndGet()).isEqualTo(2);
                return new WriteResult("a-second-opaque", 1);
            }));
            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
        }
        assertThat(a.resolve(ReadConsistency.coordinatedSession(), "resource"))
                .isEqualTo(Consistency.atLeast("a-second-opaque"));
        assertThat(b.resolve(ReadConsistency.coordinatedSession(), "member"))
                .isEqualTo(a.resolve(ReadConsistency.coordinatedSession(), "grant"));
    }

    @Test void sdkRejectsCoordinatorsThatSkipOrReplayTheMutation() {
        AtomicInteger calls = new AtomicInteger();
        CoordinatedTokenStore skips = new CoordinatedTokenStore() {
            public WriteResult executeWrite(Supplier<WriteResult> operation) { return new WriteResult("invented", 1); }
            public String readRequiredToken() { return "invented"; }
        };
        assertThatThrownBy(() -> new TokenTracker(null, skips).executeWrite(() -> {
            calls.incrementAndGet(); return new WriteResult("actual", 1);
        })).isInstanceOf(TokenStoreUnavailableException.class);
        CoordinatedTokenStore replays = new CoordinatedTokenStore() {
            public WriteResult executeWrite(Supplier<WriteResult> operation) { WriteResult first = operation.get(); assertThat(first).isNotNull(); return operation.get(); }
            public String readRequiredToken() { return "t"; }
        };
        assertThatThrownBy(() -> new TokenTracker(null, replays).executeWrite(() -> {
            calls.incrementAndGet(); return new WriteResult("actual", 1);
        })).isInstanceOf(TokenStoreUnavailableException.class);
        assertThat(calls).hasValue(1);
    }

    @Test void everySdkMutationUsesTheCoordinatorAndRelatedTypesShareTheBound() {
        SharedStore store = new SharedStore();
        TokenTracker tracker = new TokenTracker(null, store);
        AtomicInteger calls = new AtomicInteger();
        SdkTransport raw = new AuthorizationConsistencyRegressionTest.Transport() {
            private WriteResult write() { return new WriteResult("token-" + calls.incrementAndGet(), 1); }
            @Override public WriteResult writeRelationships(List<RelationshipUpdate> updates) { return write(); }
            @Override public WriteResult deleteRelationships(List<RelationshipUpdate> updates) { return write(); }
            @Override public WriteResult deleteByFilter(ResourceRef resource, SubjectRef subject, Relation relation) { return write(); }
            @Override public CheckResult check(CheckRequest request) {
                assertThat(request.consistency()).isEqualTo(Consistency.atLeast("token-3"));
                return CheckResult.denied("token-3");
            }
        };
        PolicyRegistry policy = PolicyRegistry.builder().defaultPolicy(ResourcePolicy.builder()
                .readConsistency(ReadConsistency.coordinatedSession()).build()).build();
        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(raw, policy, tracker);
        SdkTransport.RelationshipUpdate update = new SdkTransport.RelationshipUpdate(SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("grant", "g"), Relation.of("member"), SubjectRef.parse("user:u"));
        transport.writeRelationships(List.of(update));
        transport.deleteRelationships(List.of(update));
        transport.deleteByFilter(update.resource(), update.subject(), update.relation());
        assertThat(transport.check(AuthorizationConsistencyRegressionTest.request(Consistency.minimizeLatency(), null)).hasPermission()).isFalse();
    }
    @Test void requiredPolicyRejectsWeakerOverridesAndUnprovenAllowResults() {
        SharedStore store = new SharedStore();
        TokenTracker tracker = new TokenTracker(null, store);
        tracker.executeWrite(() -> new WriteResult("confirmed", 1));
        PolicyRegistry policies = PolicyRegistry.builder().defaultPolicy(ResourcePolicy.builder()
                .readConsistency(ReadConsistency.coordinatedSession()).build()).build();
        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(
                new AuthorizationConsistencyRegressionTest.Transport() {
                    @Override public CheckResult check(CheckRequest request) { return CheckResult.allowed(null); }
                }, policies, tracker);
        assertThatThrownBy(() -> transport.check(AuthorizationConsistencyRegressionTest.request(Consistency.full(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.check(AuthorizationConsistencyRegressionTest.request(Consistency.atLeast("other"), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.check(AuthorizationConsistencyRegressionTest.request(Consistency.minimizeLatency(), null)))
                .isInstanceOf(TokenStoreUnavailableException.class);
        assertThatThrownBy(() -> com.authx.sdk.AuthxClient.builder()
                .connection(c -> c.target("localhost:1").presharedKey("test"))
                .extend(e -> e.policies(policies)).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void oneNamespaceReadCoversAWholeBatchAcrossRelatedTypes() {
        AtomicInteger reads = new AtomicInteger();
        CoordinatedTokenStore store = new CoordinatedTokenStore() {
            public WriteResult executeWrite(Supplier<WriteResult> mutation) { return mutation.get(); }
            public String readRequiredToken() { reads.incrementAndGet(); return "bound"; }
        };
        PolicyRegistry policies = PolicyRegistry.builder().defaultPolicy(ResourcePolicy.builder()
                .readConsistency(ReadConsistency.coordinatedSession()).build()).build();
        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(
                new AuthorizationConsistencyRegressionTest.Transport() {
                    @Override public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
                        assertThat(consistency).isEqualTo(Consistency.atLeast("bound"));
                        return items.stream().map(item -> CheckResult.denied("actual")).toList();
                    }
                }, policies, new TokenTracker(null, store));
        transport.checkBulkMulti(List.of(new SdkTransport.BulkCheckItem(ResourceRef.of("grant", "g"), Permission.of("use"), SubjectRef.parse("user:u")),
                new SdkTransport.BulkCheckItem(ResourceRef.of("member", "m"), Permission.of("active"), SubjectRef.parse("user:u"))), Consistency.minimizeLatency());
        assertThat(reads).hasValue(1);
    }

}
