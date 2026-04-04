package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;
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
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency) {
        return intercept("CHECK", resourceType, resourceId, permission, subjectType, subjectId,
                () -> delegate.check(resourceType, resourceId, permission, subjectType, subjectId, consistency));
    }

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency, java.util.Map<String, Object> context) {
        return intercept("CHECK", resourceType, resourceId, permission, subjectType, subjectId,
                () -> delegate.check(resourceType, resourceId, permission, subjectType, subjectId, consistency, context));
    }

    @Override
    public BulkCheckResult checkBulk(String resourceType, String resourceId,
                                     String permission, List<String> subjectIds, String defaultSubjectType,
                                     Consistency consistency) {
        return intercept("CHECK_BULK", resourceType, resourceId, permission, defaultSubjectType, "",
                () -> delegate.checkBulk(resourceType, resourceId, permission, subjectIds, defaultSubjectType, consistency));
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        String resType = updates.isEmpty() ? "" : updates.getFirst().resourceType();
        String resId = updates.isEmpty() ? "" : updates.getFirst().resourceId();
        return intercept("WRITE", resType, resId, "", "", "",
                () -> delegate.writeRelationships(updates));
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        String resType = updates.isEmpty() ? "" : updates.getFirst().resourceType();
        String resId = updates.isEmpty() ? "" : updates.getFirst().resourceId();
        return intercept("DELETE", resType, resId, "", "", "",
                () -> delegate.deleteRelationships(updates));
    }

    @Override
    public List<Tuple> readRelationships(String resourceType, String resourceId,
                                          String relation, Consistency consistency) {
        return intercept("READ", resourceType, resourceId, relation, "", "",
                () -> delegate.readRelationships(resourceType, resourceId, relation, consistency));
    }

    @Override
    public List<String> lookupSubjects(String resourceType, String resourceId,
                                        String permission, String subjectType,
                                        Consistency consistency) {
        return intercept("LOOKUP_SUBJECTS", resourceType, resourceId, permission, subjectType, "",
                () -> delegate.lookupSubjects(resourceType, resourceId, permission, subjectType, consistency));
    }

    @Override
    public List<String> lookupResources(String resourceType, String permission,
                                         String subjectType, String subjectId,
                                         Consistency consistency) {
        return intercept("LOOKUP_RESOURCES", resourceType, "", permission, subjectType, subjectId,
                () -> delegate.lookupResources(resourceType, permission, subjectType, subjectId, consistency));
    }

    @Override
    public ExpandTree expand(String resourceType, String resourceId,
                              String permission, Consistency consistency) {
        return intercept("EXPAND", resourceType, resourceId, permission, "", "",
                () -> delegate.expand(resourceType, resourceId, permission, consistency));
    }

    @Override
    public void close() {
        delegate.close();
    }

    private <T> T intercept(String action, String resType, String resId,
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
