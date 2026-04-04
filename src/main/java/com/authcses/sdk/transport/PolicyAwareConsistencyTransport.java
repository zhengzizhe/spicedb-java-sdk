package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;
import com.authcses.sdk.policy.PolicyRegistry;
import com.authcses.sdk.policy.ReadConsistency;

import java.util.List;

/**
 * Replaces SessionConsistencyTransport. Uses PolicyRegistry to resolve per-resource-type
 * ReadConsistency, and TokenTracker to map it to concrete SpiceDB Consistency.
 *
 * <p>When the user passes minimizeLatency() but the policy for this resource type says SESSION,
 * we upgrade to atLeast(lastWriteToken). If the policy says BOUNDED_STALENESS(5s),
 * we use a token from 5 seconds ago.
 *
 * <p>When the user explicitly passes a non-default consistency (full(), atLeast(), atExactSnapshot()),
 * we respect it — explicit always wins over policy.
 */
public class PolicyAwareConsistencyTransport extends ForwardingTransport {

    private final SdkTransport delegate;
    private final PolicyRegistry policyRegistry;
    private final TokenTracker tokenTracker;

    public PolicyAwareConsistencyTransport(SdkTransport delegate, PolicyRegistry policyRegistry, TokenTracker tokenTracker) {
        this.delegate = delegate;
        this.policyRegistry = policyRegistry;
        this.tokenTracker = tokenTracker;
    }

    @Override
    protected SdkTransport delegate() {
        return delegate;
    }

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency) {
        var result = delegate.check(resourceType, resourceId, permission, subjectType, subjectId,
                resolveConsistency(resourceType, consistency));
        tokenTracker.recordRead(result.zedToken());
        return result;
    }

    @Override
    public BulkCheckResult checkBulk(String resourceType, String resourceId,
                                     String permission, List<String> subjectIds, String defaultSubjectType,
                                     Consistency consistency) {
        return delegate.checkBulk(resourceType, resourceId, permission, subjectIds, defaultSubjectType,
                resolveConsistency(resourceType, consistency));
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        var result = delegate.writeRelationships(updates);
        tokenTracker.recordWrite(result.zedToken());
        return result;
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        var result = delegate.deleteRelationships(updates);
        tokenTracker.recordWrite(result.zedToken());
        return result;
    }

    @Override
    public List<Tuple> readRelationships(String resourceType, String resourceId,
                                          String relation, Consistency consistency) {
        return delegate.readRelationships(resourceType, resourceId, relation,
                resolveConsistency(resourceType, consistency));
    }

    @Override
    public List<String> lookupSubjects(String resourceType, String resourceId,
                                        String permission, String subjectType,
                                        Consistency consistency) {
        return delegate.lookupSubjects(resourceType, resourceId, permission, subjectType,
                resolveConsistency(resourceType, consistency));
    }

    @Override
    public List<String> lookupResources(String resourceType, String permission,
                                         String subjectType, String subjectId,
                                         Consistency consistency) {
        return delegate.lookupResources(resourceType, permission, subjectType, subjectId,
                resolveConsistency(resourceType, consistency));
    }

    @Override
    public RevokeResult deleteByFilter(String resourceType, String resourceId,
                                        String subjectType, String subjectId,
                                        String optionalRelation) {
        var result = delegate.deleteByFilter(resourceType, resourceId, subjectType, subjectId, optionalRelation);
        tokenTracker.recordWrite(result.zedToken());
        return result;
    }

    /**
     * If the user passed minimizeLatency (the default), apply the policy for this resource type.
     * If the user explicitly passed full/atLeast/atExactSnapshot, respect it.
     */
    private Consistency resolveConsistency(String resourceType, Consistency requested) {
        if (!(requested instanceof Consistency.MinimizeLatency)) {
            return requested; // explicit user choice wins
        }

        ReadConsistency policy = policyRegistry.resolveReadConsistency(resourceType);
        return tokenTracker.resolve(policy);
    }
}
