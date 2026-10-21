package com.authx.sdk.transport;

import com.authx.sdk.exception.AuthxTimeoutException;
import com.authx.sdk.model.*;
import com.authx.sdk.policy.PolicyRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** One deadline across token lookup, coalescing wait, retries, and each resulting RPC. */
public final class DeadlineTransport extends ForwardingTransport {
    private final SdkTransport delegate;
    private final PolicyRegistry policies;
    private final long timeoutMillis;

    public DeadlineTransport(SdkTransport delegate, PolicyRegistry policies, long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Request timeout must be positive");
        this.delegate = delegate;
        this.policies = policies;
        this.timeoutMillis = timeoutMillis;
    }
    @Override protected SdkTransport delegate() { return delegate; }

    private <T> T bounded(String type, Supplier<T> action) {
        return bounded(List.of(type), action);
    }

    private <T> T bounded(List<String> types, Supplier<T> action) {
        long millis = timeoutMillis;
        for (String type : types) {
            Duration configured = policies.resolve(type).timeout();
            if (configured != null) millis = Math.min(millis, configured.toMillis());
        }
        if (millis <= 0 || io.grpc.Context.current().isCancelled()) {
            throw new AuthxTimeoutException("SDK request deadline expired or was cancelled");
        }
        io.grpc.Context.CancellableContext context = io.grpc.Context.current()
                .withDeadlineAfter(millis, TimeUnit.MILLISECONDS, GrpcTransport.DEADLINE_SCHEDULER);
        io.grpc.Context previous = context.attach();
        try {
            T result = action.get();
            if (context.isCancelled()) throw new AuthxTimeoutException("SDK request deadline expired");
            return result;
        } finally {
            context.detach(previous);
            context.cancel(null);
        }
    }
    @Override public CheckResult check(CheckRequest request) {
        return bounded(request.resource().type(), () -> delegate.check(request));
    }
    @Override public BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects) {
        return bounded(request.resource().type(), () -> delegate.checkBulk(request, subjects));
    }
    @Override public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
        if (items.isEmpty()) return List.of();
        return bounded(items.stream().map(item -> item.resource().type()).toList(), () -> delegate.checkBulkMulti(items, consistency));
    }
    @Override public WriteResult writeRelationships(List<RelationshipUpdate> updates) {
        return bounded(updates.stream().map(update -> update.resource().type()).toList(), () -> delegate.writeRelationships(updates));
    }
    @Override public WriteResult writeRelationships(WriteRequest request) {
        return bounded(request.updates().stream().map(update -> update.resource().type()).toList(), () -> delegate.writeRelationships(request));
    }
    @Override public WriteResult deleteRelationships(List<RelationshipUpdate> updates) {
        return bounded(updates.stream().map(update -> update.resource().type()).toList(), () -> delegate.deleteRelationships(updates));
    }
    @Override public WriteResult deleteByFilter(ResourceRef resource, SubjectRef subject, Relation relation) {
        return bounded(resource.type(), () -> delegate.deleteByFilter(resource, subject, relation));
    }
    @Override public List<Tuple> readRelationships(ResourceRef resource, Relation relation, Consistency consistency) {
        return bounded(resource.type(), () -> delegate.readRelationships(resource, relation, consistency));
    }
    @Override public List<RelationshipRecord> readRelationshipDetails(ResourceRef resource, Relation relation, Consistency consistency) {
        return bounded(resource.type(), () -> delegate.readRelationshipDetails(resource, relation, consistency));
    }
    @Override public List<SubjectRef> lookupSubjects(LookupSubjectsRequest request) {
        return bounded(request.resource().type(), () -> delegate.lookupSubjects(request));
    }
    @Override public List<ResourceRef> lookupResources(LookupResourcesRequest request) {
        return bounded(request.resourceType(), () -> delegate.lookupResources(request));
    }
    @Override public ExpandTree expand(ResourceRef resource, Permission permission, Consistency consistency) {
        return bounded(resource.type(), () -> delegate.expand(resource, permission, consistency));
    }
}
