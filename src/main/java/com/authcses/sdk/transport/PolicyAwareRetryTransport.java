package com.authcses.sdk.transport;

import com.authcses.sdk.exception.AuthCsesConnectionException;
import com.authcses.sdk.model.*;
import com.authcses.sdk.policy.PolicyRegistry;
import com.authcses.sdk.policy.RetryPolicy;

import java.util.List;
import java.util.function.Supplier;

/**
 * Policy-aware retry: resolves per-resource-type RetryPolicy from PolicyRegistry.
 */
public class PolicyAwareRetryTransport implements SdkTransport {

    private static final System.Logger LOG = System.getLogger(PolicyAwareRetryTransport.class.getName());

    private final SdkTransport delegate;
    private final PolicyRegistry policyRegistry;

    public PolicyAwareRetryTransport(SdkTransport delegate, PolicyRegistry policyRegistry) {
        this.delegate = delegate;
        this.policyRegistry = policyRegistry;
    }

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency) {
        return withRetry(policyRegistry.resolve(resourceType).getRetry(),
                () -> delegate.check(resourceType, resourceId, permission, subjectType, subjectId, consistency));
    }

    @Override
    public BulkCheckResult checkBulk(String resourceType, String resourceId,
                                     String permission, List<String> subjectIds, String defaultSubjectType,
                                     Consistency consistency) {
        return withRetry(policyRegistry.resolve(resourceType).getRetry(),
                () -> delegate.checkBulk(resourceType, resourceId, permission, subjectIds, defaultSubjectType, consistency));
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        // Resolve retry from first update's resource type (batch usually same type)
        String resourceType = updates.isEmpty() ? "" : updates.getFirst().resourceType();
        return withRetry(policyRegistry.resolve(resourceType).getRetry(),
                () -> delegate.writeRelationships(updates));
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        String resourceType = updates.isEmpty() ? "" : updates.getFirst().resourceType();
        return withRetry(policyRegistry.resolve(resourceType).getRetry(),
                () -> delegate.deleteRelationships(updates));
    }

    @Override
    public List<Tuple> readRelationships(String resourceType, String resourceId,
                                          String relation, Consistency consistency) {
        return withRetry(policyRegistry.resolve(resourceType).getRetry(),
                () -> delegate.readRelationships(resourceType, resourceId, relation, consistency));
    }

    @Override
    public List<String> lookupSubjects(String resourceType, String resourceId,
                                        String permission, String subjectType,
                                        Consistency consistency) {
        return withRetry(policyRegistry.resolve(resourceType).getRetry(),
                () -> delegate.lookupSubjects(resourceType, resourceId, permission, subjectType, consistency));
    }

    @Override
    public List<String> lookupResources(String resourceType, String permission,
                                         String subjectType, String subjectId,
                                         Consistency consistency) {
        return withRetry(policyRegistry.resolve(resourceType).getRetry(),
                () -> delegate.lookupResources(resourceType, permission, subjectType, subjectId, consistency));
    }

    @Override
    public void close() {
        delegate.close();
    }

    private <T> T withRetry(RetryPolicy policy, Supplier<T> call) {
        if (policy == null || policy.getMaxAttempts() <= 0) {
            return call.get();
        }

        Exception lastException = null;
        for (int attempt = 0; attempt <= policy.getMaxAttempts(); attempt++) {
            try {
                return call.get();
            } catch (Exception e) {
                if (!policy.shouldRetry(e)) throw e;
                lastException = e;
                if (attempt < policy.getMaxAttempts()) {
                    var delay = policy.delayForAttempt(attempt);
                    LOG.log(System.Logger.Level.WARNING,
                            "Retry {0}/{1} after {2}ms: {3}",
                            attempt + 1, policy.getMaxAttempts(), delay.toMillis(), e.getMessage());
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AuthCsesConnectionException("Retry interrupted", ie);
                    }
                }
            }
        }
        if (lastException instanceof RuntimeException re) throw re;
        throw new AuthCsesConnectionException("All retry attempts exhausted", lastException);
    }
}
