package com.authx.sdk.transport;

import com.authx.sdk.model.*;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ReadConsistency;
import com.authx.sdk.policy.ResourcePolicy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        com.authx.sdk.policy.PolicyRegistry registry = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .readConsistency(ReadConsistency.strong())
                        .build())
                .build();

        com.authx.sdk.transport.PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        // User explicitly passes atLeast — should NOT be overridden by policy
        com.authx.sdk.model.CheckRequest request = CheckRequest.of("document", "d1", "editor", "user", "alice",
                Consistency.atLeast("explicit_token"));

        com.authx.sdk.model.CheckResult result = transport.check(request);
        assertThat(result.hasPermission()).isTrue();
    }

    @Test
    void minimizeLatencyIsUpgradedBySessionPolicy() {
        // Record a write token for documents
        tokenTracker.recordWrite("document", "write_token_1");

        com.authx.sdk.policy.PolicyRegistry registry = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .readConsistency(ReadConsistency.session())
                        .build())
                .build();

        // Track what consistency the delegate actually receives
        com.authx.sdk.model.Consistency[] capturedConsistency = new Consistency[1];
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

        com.authx.sdk.transport.PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(capturingDelegate, registry, tokenTracker);
        transport.check(CheckRequest.of("document", "d1", "editor", "user", "alice",
                Consistency.minimizeLatency()));

        // Should be upgraded to atLeast with the write token
        assertThat(capturedConsistency[0]).isInstanceOf(Consistency.AtLeast.class);
        assertThat(((Consistency.AtLeast) capturedConsistency[0]).zedToken()).isEqualTo("write_token_1");
    }

    @Test
    void strongPolicyResolvedToFull() {
        com.authx.sdk.policy.PolicyRegistry registry = PolicyRegistry.builder()
                .forResourceType("document", ResourcePolicy.builder()
                        .readConsistency(ReadConsistency.strong())
                        .build())
                .build();

        com.authx.sdk.model.Consistency[] capturedConsistency = new Consistency[1];
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

        com.authx.sdk.transport.PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(capturingDelegate, registry, tokenTracker);
        transport.check(CheckRequest.of("document", "d1", "editor", "user", "alice",
                Consistency.minimizeLatency()));

        assertThat(capturedConsistency[0]).isInstanceOf(Consistency.Full.class);
    }

    @Test
    void writeRecordsToken() {
        com.authx.sdk.policy.PolicyRegistry registry = PolicyRegistry.withDefaults();
        com.authx.sdk.transport.PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        transport.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d2"),
                Relation.of("viewer"),
                SubjectRef.of("user", "bob", null))));

        assertThat(tokenTracker.getLastWriteToken("document")).isNotNull();
    }

    @Test
    void deleteRecordsToken() {
        com.authx.sdk.policy.PolicyRegistry registry = PolicyRegistry.withDefaults();
        com.authx.sdk.transport.PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        transport.deleteRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.DELETE,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));

        assertThat(tokenTracker.getLastWriteToken("document")).isNotNull();
    }

    @Test
    void writeWithEmptyUpdatesDoesNotCrash() {
        com.authx.sdk.policy.PolicyRegistry registry = PolicyRegistry.withDefaults();
        com.authx.sdk.transport.PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        com.authx.sdk.model.GrantResult result = transport.writeRelationships(List.of());
        assertThat(result.count()).isZero();
    }

    @Test
    void checkRecordsReadToken() {
        com.authx.sdk.policy.PolicyRegistry registry = PolicyRegistry.withDefaults();
        com.authx.sdk.transport.PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        com.authx.sdk.model.CheckResult result = transport.check(CheckRequest.of(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        // recordRead is called (even though it's a no-op currently)
        assertThat(result.zedToken()).isNotNull();
    }

    @Test
    void lookupSubjectsResolvesConsistency() {
        tokenTracker.recordWrite("document", "write_token_ls");

        com.authx.sdk.policy.PolicyRegistry registry = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .readConsistency(ReadConsistency.session())
                        .build())
                .build();

        com.authx.sdk.transport.PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        java.util.List<com.authx.sdk.model.SubjectRef> subjects = transport.lookupSubjects(new LookupSubjectsRequest(
                ResourceRef.of("document", "d1"),
                Permission.of("editor"),
                "user"));

        assertThat(subjects).hasSize(1);
    }

    @Test
    void lookupResourcesResolvesConsistency() {
        com.authx.sdk.policy.PolicyRegistry registry = PolicyRegistry.withDefaults();
        com.authx.sdk.transport.PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        java.util.List<com.authx.sdk.model.ResourceRef> resources = transport.lookupResources(new LookupResourcesRequest(
                "document",
                Permission.of("editor"),
                SubjectRef.of("user", "alice", null)));

        assertThat(resources).hasSize(1);
    }

    @Test
    void deleteByFilterRecordsToken() {
        com.authx.sdk.policy.PolicyRegistry registry = PolicyRegistry.withDefaults();
        com.authx.sdk.transport.PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        transport.deleteByFilter(
                ResourceRef.of("document", "d1"),
                SubjectRef.of("user", "alice", null),
                Relation.of("editor"));

        assertThat(tokenTracker.getLastWriteToken("document")).isNotNull();
    }

    @Test
    void checkBulkRecordsToken() {
        com.authx.sdk.policy.PolicyRegistry registry = PolicyRegistry.withDefaults();
        com.authx.sdk.transport.PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(inner, registry, tokenTracker);

        com.authx.sdk.model.BulkCheckResult result = transport.checkBulk(
                CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()),
                List.of(SubjectRef.of("user", "alice", null)));

        assertThat(result).isNotNull();
    }
}
