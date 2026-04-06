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
    public CheckResult check(CheckRequest request) {
        var adjusted = request.withConsistency(
                resolveConsistency(request.resource().type(), request.consistency()));
        var result = delegate.check(adjusted);
        tokenTracker.recordRead(result.zedToken());
        return result;
    }

    @Override
    public BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects) {
        var adjusted = request.withConsistency(
                resolveConsistency(request.resource().type(), request.consistency()));
        return delegate.checkBulk(adjusted, subjects);
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        var result = delegate.writeRelationships(updates);
        String resType = updates.isEmpty() ? null : updates.getFirst().resource().type();
        tokenTracker.recordWrite(resType, result.zedToken());
        return result;
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        var result = delegate.deleteRelationships(updates);
        String resType = updates.isEmpty() ? null : updates.getFirst().resource().type();
        tokenTracker.recordWrite(resType, result.zedToken());
        return result;
    }

    @Override
    public List<Tuple> readRelationships(ResourceRef resource, Relation relation, Consistency consistency) {
        return delegate.readRelationships(resource, relation,
                resolveConsistency(resource.type(), consistency));
    }

    @Override
    public List<SubjectRef> lookupSubjects(LookupSubjectsRequest request) {
        var adjusted = request.withConsistency(
                resolveConsistency(request.resource().type(), request.consistency()));
        return delegate.lookupSubjects(adjusted);
    }

    @Override
    public List<ResourceRef> lookupResources(LookupResourcesRequest request) {
        var adjusted = request.withConsistency(
                resolveConsistency(request.resourceType(), request.consistency()));
        return delegate.lookupResources(adjusted);
    }

    @Override
    public RevokeResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                        Relation optionalRelation) {
        var result = delegate.deleteByFilter(resource, subject, optionalRelation);
        tokenTracker.recordWrite(resource.type(), result.zedToken());
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
        return tokenTracker.resolve(policy, resourceType);
    }
}
