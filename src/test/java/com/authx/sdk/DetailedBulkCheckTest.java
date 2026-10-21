package com.authx.sdk;

import com.authx.sdk.exception.AuthxProtocolException;
import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
import com.authx.sdk.transport.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DetailedBulkCheckTest {
    @Test void preservesDuplicateCellsTheirContextsAndConditionalOutcomes() {
        AtomicReference<List<SdkTransport.BulkCheckItem>> received = new AtomicReference<>();
        SdkTransport transport = new ForwardingTransport() {
            @Override protected SdkTransport delegate() { throw new UnsupportedOperationException(); }
            @Override public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
                received.set(items);
                return List.of(CheckResult.allowed("t"), CheckResult.denied("t"),
                        new CheckResult(Permissionship.CONDITIONAL_PERMISSION, "t"));
            }
        };
        SubjectRef subject = SubjectRef.parse("user:u");
        DetailedBulkCheckResult result = new BatchCheckBuilder(transport)
                .add("document", "d", "view", subject, Map.of("now", 1))
                .add("document", "d", "view", subject, Map.of("now", 2))
                .add("document", "d", "view", subject)
                .withConsistency(Consistency.atLeast("t"))
                .fetchInSingleSnapshot();
        assertThat(received.get()).hasSize(3);
        assertThat(received.get().get(1).caveatContext()).containsEntry("now", 2);
        assertThat(result.items()).hasSize(3);
        assertThat(result.items().get(0).result().hasPermission()).isTrue();
        assertThat(result.items().get(1).result().hasPermission()).isFalse();
        assertThat(result.items().get(2).result().isConditional()).isTrue();
        assertThat(result.requireSingleSnapshotToken()).isEqualTo("t");
    }

    @Test void refusesToPresentMixedRevisionResultsAsOneSnapshot() {
        SdkTransport transport = new ForwardingTransport() {
            @Override protected SdkTransport delegate() { throw new UnsupportedOperationException(); }
            @Override public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
                return List.of(CheckResult.allowed("first"), CheckResult.allowed("second"));
            }
        };
        BatchCheckBuilder builder = new BatchCheckBuilder(transport)
                .add("document", "d1", "view", SubjectRef.parse("user:u"))
                .add("document", "d2", "view", SubjectRef.parse("user:u"));
        assertThatThrownBy(builder::fetchInSingleSnapshot).isInstanceOf(AuthxProtocolException.class);
        assertThat(builder.fetchDetailed().items()).hasSize(2);
    }

    @Test void detailedChecksRejectIncompleteResultsButLegacyMatrixStaysFailClosed() {
        SdkTransport transport = new ForwardingTransport() {
            @Override protected SdkTransport delegate() { throw new UnsupportedOperationException(); }
            @Override public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) { return List.of(); }
        };
        BatchCheckBuilder builder = new BatchCheckBuilder(transport).add("document", "d", "view", SubjectRef.parse("user:u"));
        assertThatThrownBy(builder::fetchDetailed).isInstanceOf(AuthxProtocolException.class);
        assertThat(builder.fetch().allowed("document:d", "view", "user:u")).isFalse();
    }

    @Test void contextSurvivesDefaultSequentialTransportFallback() {
        AtomicReference<Map<String, Object>> context = new AtomicReference<>();
        SdkTransport transport = new InMemoryTransport() {
            @Override public CheckResult check(CheckRequest request) { context.set(request.caveatContext()); return CheckResult.allowed("t"); }
        };
        new BatchCheckBuilder(transport).add("document", "d", "view", SubjectRef.parse("user:u"), Map.of("region", "cn")).fetchDetailed();
        assertThat(context.get()).containsEntry("region", "cn");
    }
}
