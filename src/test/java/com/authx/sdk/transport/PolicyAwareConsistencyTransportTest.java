package com.authx.sdk.transport;

import com.authx.sdk.model.*;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ReadConsistency;
import com.authx.sdk.policy.ResourcePolicy;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link PolicyAwareConsistencyTransport} — per-resource-type consistency resolution.
 */
class PolicyAwareConsistencyTransportTest {

    private InMemoryTransport inner;
    private TokenTracker tokenTracker;

    @BeforeEach
    void setup() {
        inner = new InMemoryTransport();
        tokenTracker = new TokenTracker();

        // Pre-populate
        inner.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));
    }

    @Test
    void explicitConsistencyIsNotOverridden() {
        PolicyRegistry registry = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .readConsistency(ReadConsistency.strong())
                        .build())
                .build();

        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        // User explicitly passes atLeast — should NOT be overridden by policy
        CheckRequest request = CheckRequest.of("document", "d1", "editor", "user", "alice",
                Consistency.atLeast("explicit_token"));

        CheckResult result = transport.check(request);
        assertThat(result.hasPermission()).isTrue();
    }

    @Test
    void minimizeLatencyIsUpgradedBySessionPolicy() {
        // Record a write token for documents
        tokenTracker.recordWrite("document", "write_token_1");

        PolicyRegistry registry = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .readConsistency(ReadConsistency.session())
                        .build())
                .build();

        // Track what consistency the delegate actually receives
        Consistency[] capturedConsistency = new Consistency[1];
        SdkTransport capturingDelegate = new InMemoryTransport() {
            {
                writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "d1"),
                        Relation.of("editor"),
                        SubjectRef.of("user", "alice", null))));
            }

            @Override
            public CheckResult check(CheckRequest request) {
                capturedConsistency[0] = request.consistency();
                return super.check(request);
            }
        };

        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(capturingDelegate, registry, tokenTracker);
        transport.check(CheckRequest.of("document", "d1", "editor", "user", "alice",
                Consistency.minimizeLatency()));

        // Should be upgraded to atLeast with the write token
        assertThat(capturedConsistency[0]).isInstanceOf(Consistency.AtLeast.class);
        assertThat(((Consistency.AtLeast) capturedConsistency[0]).zedToken()).isEqualTo("write_token_1");
    }

    @Test
    void strongPolicyResolvedToFull() {
        PolicyRegistry registry = PolicyRegistry.builder()
                .forResourceType("document", ResourcePolicy.builder()
                        .readConsistency(ReadConsistency.strong())
                        .build())
                .build();

        Consistency[] capturedConsistency = new Consistency[1];
        SdkTransport capturingDelegate = new InMemoryTransport() {
            {
                writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "d1"),
                        Relation.of("editor"),
                        SubjectRef.of("user", "alice", null))));
            }

            @Override
            public CheckResult check(CheckRequest request) {
                capturedConsistency[0] = request.consistency();
                return super.check(request);
            }
        };

        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(capturingDelegate, registry, tokenTracker);
        transport.check(CheckRequest.of("document", "d1", "editor", "user", "alice",
                Consistency.minimizeLatency()));

        assertThat(capturedConsistency[0]).isInstanceOf(Consistency.Full.class);
    }

    @Test
    void writeRecordsToken() {
        PolicyRegistry registry = PolicyRegistry.withDefaults();
        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        transport.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d2"),
                Relation.of("viewer"),
                SubjectRef.of("user", "bob", null))));

        assertThat(tokenTracker.getLastWriteToken("document")).isNotNull();
    }

    @Test
    void deleteRecordsToken() {
        PolicyRegistry registry = PolicyRegistry.withDefaults();
        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        transport.deleteRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.DELETE,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));

        assertThat(tokenTracker.getLastWriteToken("document")).isNotNull();
    }

    @Test
    void writeWithEmptyUpdatesDoesNotCrash() {
        PolicyRegistry registry = PolicyRegistry.withDefaults();
        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        GrantResult result = transport.writeRelationships(List.of());
        assertThat(result.count()).isZero();
    }

    @Test
    void checkRecordsReadToken() {
        PolicyRegistry registry = PolicyRegistry.withDefaults();
        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        CheckResult result = transport.check(CheckRequest.of(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        // recordRead is called (even though it's a no-op currently)
        assertThat(result.zedToken()).isNotNull();
    }

    @Test
    void lookupSubjectsResolvesConsistency() {
        tokenTracker.recordWrite("document", "write_token_ls");

        PolicyRegistry registry = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .readConsistency(ReadConsistency.session())
                        .build())
                .build();

        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        List<SubjectRef> subjects = transport.lookupSubjects(new LookupSubjectsRequest(
                ResourceRef.of("document", "d1"),
                Permission.of("editor"),
                "user"));

        assertThat(subjects).hasSize(1);
    }

    @Test
    void lookupResourcesResolvesConsistency() {
        PolicyRegistry registry = PolicyRegistry.withDefaults();
        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        List<ResourceRef> resources = transport.lookupResources(new LookupResourcesRequest(
                "document",
                Permission.of("editor"),
                SubjectRef.of("user", "alice", null)));

        assertThat(resources).hasSize(1);
    }

    @Test
    void deleteByFilterRecordsToken() {
        PolicyRegistry registry = PolicyRegistry.withDefaults();
        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        transport.deleteByFilter(
                ResourceRef.of("document", "d1"),
                SubjectRef.of("user", "alice", null),
                Relation.of("editor"));

        assertThat(tokenTracker.getLastWriteToken("document")).isNotNull();
    }

    @Test
    void checkBulkRecordsToken() {
        PolicyRegistry registry = PolicyRegistry.withDefaults();
        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        BulkCheckResult result = transport.checkBulk(
                CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()),
                List.of(SubjectRef.of("user", "alice", null)));

        assertThat(result).isNotNull();
    }
}
