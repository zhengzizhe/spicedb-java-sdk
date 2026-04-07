package com.authx.sdk.transport;

import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.SdkAction;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.spi.SdkInterceptor.OperationContext;

import java.util.List;
import java.util.function.Supplier;

/**
 * Runs registered interceptors for each operation on the delegate transport.
 *
 * <p>For {@code check()} and {@code writeRelationships()}, uses OkHttp-style chains
 * ({@link RealCheckChain} / {@link RealWriteChain}) so interceptors can modify requests,
 * short-circuit, or wrap errors.
 *
 * <p>Other operations (lookup, read, expand, delete) are wrapped with
 * {@link SdkInterceptor#beforeOperation}/{@link SdkInterceptor#afterOperation}
 * so that cross-cutting concerns like rate limiting and bulkhead still apply.
 */
public class InterceptorTransport extends ForwardingTransport {

    private final SdkTransport delegate;
    private final List<SdkInterceptor> interceptors;

    public InterceptorTransport(SdkTransport delegate, List<SdkInterceptor> interceptors) {
        this.delegate = delegate;
        this.interceptors = List.copyOf(interceptors);
    }

    @Override
    protected SdkTransport delegate() {
        return delegate;
    }

    // ---- Chain-based operations ----

    @Override
    public CheckResult check(CheckRequest request) {
        if (interceptors.isEmpty()) return delegate.check(request);

        var ctx = buildCheckContext(request);
        var chain = new RealCheckChain(interceptors, 0, request, delegate, ctx);
        return chain.proceed(request);
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        if (interceptors.isEmpty()) return delegate.writeRelationships(updates);

        String resType = updates.isEmpty() ? "" : updates.getFirst().resource().type();
        String resId = updates.isEmpty() ? "" : updates.getFirst().resource().id();
        var ctx = new OperationContext(SdkAction.WRITE, resType, resId, "", "", "");
        var writeRequest = new WriteRequest(updates);
        var chain = new RealWriteChain(interceptors, 0, writeRequest, delegate, ctx);
        return chain.proceed(writeRequest);
    }

    // ---- Hook-wrapped operations ----

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        if (interceptors.isEmpty()) return delegate.deleteRelationships(updates);

        String resType = updates.isEmpty() ? "" : updates.getFirst().resource().type();
        String resId = updates.isEmpty() ? "" : updates.getFirst().resource().id();
        var ctx = new OperationContext(SdkAction.DELETE, resType, resId, "", "", "");
        return wrapOperation(ctx, () -> delegate.deleteRelationships(updates));
    }

    @Override
    public RevokeResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                        Relation optionalRelation) {
        if (interceptors.isEmpty()) return delegate.deleteByFilter(resource, subject, optionalRelation);

        var ctx = new OperationContext(SdkAction.DELETE, resource.type(), resource.id(),
                "", subject.type(), subject.id());
        return wrapOperation(ctx, () -> delegate.deleteByFilter(resource, subject, optionalRelation));
    }

    @Override
    public List<Tuple> readRelationships(ResourceRef resource, Relation relation,
                                          Consistency consistency) {
        if (interceptors.isEmpty()) return delegate.readRelationships(resource, relation, consistency);

        var ctx = new OperationContext(SdkAction.READ, resource.type(), resource.id(),
                relation != null ? relation.name() : "", "", "");
        return wrapOperation(ctx, () -> delegate.readRelationships(resource, relation, consistency));
    }

    @Override
    public List<SubjectRef> lookupSubjects(LookupSubjectsRequest request) {
        if (interceptors.isEmpty()) return delegate.lookupSubjects(request);

        var ctx = new OperationContext(SdkAction.LOOKUP_SUBJECTS,
                request.resource().type(), request.resource().id(),
                request.permission().name(), request.subjectType(), "");
        return wrapOperation(ctx, () -> delegate.lookupSubjects(request));
    }

    @Override
    public List<ResourceRef> lookupResources(LookupResourcesRequest request) {
        if (interceptors.isEmpty()) return delegate.lookupResources(request);

        var ctx = new OperationContext(SdkAction.LOOKUP_RESOURCES,
                request.resourceType(), "",
                request.permission().name(),
                request.subject().type(), request.subject().id());
        return wrapOperation(ctx, () -> delegate.lookupResources(request));
    }

    @Override
    public ExpandTree expand(ResourceRef resource, Permission permission,
                              Consistency consistency) {
        if (interceptors.isEmpty()) return delegate.expand(resource, permission, consistency);

        var ctx = new OperationContext(SdkAction.EXPAND, resource.type(), resource.id(),
                permission.name(), "", "");
        return wrapOperation(ctx, () -> delegate.expand(resource, permission, consistency));
    }

    @Override
    public BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects) {
        if (interceptors.isEmpty()) return delegate.checkBulk(request, subjects);

        var ctx = new OperationContext(SdkAction.CHECK_BULK,
                request.resource().type(), request.resource().id(),
                request.permission().name(),
                request.subject().type(), request.subject().id());
        return wrapOperation(ctx, () -> delegate.checkBulk(request, subjects));
    }

    @Override
    public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
        if (interceptors.isEmpty()) return delegate.checkBulkMulti(items, consistency);

        String resType = items.isEmpty() ? "" : items.getFirst().resource().type();
        var ctx = new OperationContext(SdkAction.CHECK_BULK, resType, "", "", "", "");
        return wrapOperation(ctx, () -> delegate.checkBulkMulti(items, consistency));
    }

    @Override
    public void close() {
        delegate.close();
    }

    // ---- Internal helpers ----

    /**
     * Wraps an operation with beforeOperation/afterOperation hooks from all interceptors.
     * Ensures afterOperation is always called if beforeOperation succeeded, even on failure.
     */
    private <T> T wrapOperation(OperationContext ctx, Supplier<T> operation) {
        int acquired = 0;
        try {
            for (var interceptor : interceptors) {
                interceptor.beforeOperation(ctx);
                acquired++;
            }
            return operation.get();
        } finally {
            // Release in reverse order, only for interceptors that completed beforeOperation
            for (int i = acquired - 1; i >= 0; i--) {
                interceptors.get(i).afterOperation(ctx);
            }
        }
    }

    private OperationContext buildCheckContext(CheckRequest request) {
        return new OperationContext(SdkAction.CHECK,
                request.resource().type(), request.resource().id(),
                request.permission().name(),
                request.subject().type(), request.subject().id());
    }
}
