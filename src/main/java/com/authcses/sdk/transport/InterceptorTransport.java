package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;
import com.authcses.sdk.model.enums.SdkAction;
import com.authcses.sdk.spi.SdkInterceptor;
import com.authcses.sdk.spi.SdkInterceptor.OperationContext;

import java.util.List;
import java.util.function.Supplier;

/**
 * Runs registered interceptors before/after each operation on the delegate transport.
 */
public class InterceptorTransport extends ForwardingTransport {

    private static final System.Logger LOG = System.getLogger(InterceptorTransport.class.getName());

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

    @Override
    public CheckResult check(CheckRequest request) {
        return intercept(SdkAction.CHECK,
                request.resource().type(), request.resource().id(),
                request.permission().name(),
                request.subject().type(), request.subject().id(),
                () -> delegate.check(request));
    }

    @Override
    public BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects) {
        return intercept(SdkAction.CHECK_BULK,
                request.resource().type(), request.resource().id(),
                request.permission().name(),
                request.subject().type(), "",
                () -> delegate.checkBulk(request, subjects));
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        String resType = updates.isEmpty() ? "" : updates.getFirst().resource().type();
        String resId = updates.isEmpty() ? "" : updates.getFirst().resource().id();
        return intercept(SdkAction.WRITE, resType, resId, "", "", "",
                () -> delegate.writeRelationships(updates));
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        String resType = updates.isEmpty() ? "" : updates.getFirst().resource().type();
        String resId = updates.isEmpty() ? "" : updates.getFirst().resource().id();
        return intercept(SdkAction.DELETE, resType, resId, "", "", "",
                () -> delegate.deleteRelationships(updates));
    }

    @Override
    public List<Tuple> readRelationships(ResourceRef resource, Relation relation, Consistency consistency) {
        return intercept(SdkAction.READ, resource.type(), resource.id(),
                relation != null ? relation.name() : "", "", "",
                () -> delegate.readRelationships(resource, relation, consistency));
    }

    @Override
    public List<String> lookupSubjects(LookupSubjectsRequest request, Consistency consistency) {
        return intercept(SdkAction.LOOKUP_SUBJECTS,
                request.resource().type(), request.resource().id(),
                request.permission().name(), request.subjectType(), "",
                () -> delegate.lookupSubjects(request, consistency));
    }

    @Override
    public List<String> lookupResources(LookupResourcesRequest request, Consistency consistency) {
        return intercept(SdkAction.LOOKUP_RESOURCES,
                request.resourceType(), "",
                request.permission().name(),
                request.subject().type(), request.subject().id(),
                () -> delegate.lookupResources(request, consistency));
    }

    @Override
    public ExpandTree expand(ResourceRef resource, Permission permission, Consistency consistency) {
        return intercept(SdkAction.EXPAND, resource.type(), resource.id(),
                permission.name(), "", "",
                () -> delegate.expand(resource, permission, consistency));
    }

    @Override
    public void close() {
        delegate.close();
    }

    private <T> T intercept(SdkAction action, String resType, String resId,
                             String perm, String subType, String subId,
                             Supplier<T> call) {
        var ctx = new OperationContext(action, resType, resId, perm, subType, subId);

        // Before (in order)
        for (var interceptor : interceptors) {
            try {
                interceptor.before(ctx);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Interceptor before() failed: {0}", e.getMessage());
                throw e; // interceptor can abort by throwing
            }
        }

        long start = System.nanoTime();
        try {
            T result = call.get();
            ctx.setDurationMs((System.nanoTime() - start) / 1_000_000);
            ctx.setResult("SUCCESS");
            return result;
        } catch (Exception e) {
            ctx.setDurationMs((System.nanoTime() - start) / 1_000_000);
            ctx.setResult("ERROR");
            ctx.setError(e);
            throw e;
        } finally {
            // After (in reverse order)
            for (int i = interceptors.size() - 1; i >= 0; i--) {
                try {
                    interceptors.get(i).after(ctx);
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.WARNING, "Interceptor after() failed: {0}", e.getMessage());
                }
            }
        }
    }
}
