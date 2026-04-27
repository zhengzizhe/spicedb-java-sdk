package com.authx.sdk.transport;

import com.authx.sdk.model.*;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ReadConsistency;
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
        CheckRequest adjusted = request.withConsistency(
                resolveConsistency(request.resource().type(), request.consistency()));
        CheckResult result = delegate.check(adjusted);
        tokenTracker.recordRead(result.zedToken());
        return result;
    }

    @Override
    public BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects) {
        CheckRequest adjusted = request.withConsistency(
                resolveConsistency(request.resource().type(), request.consistency()));
        BulkCheckResult result = delegate.checkBulk(adjusted, subjects);
        // F12-2: record the response zedToken so subsequent reads in the same
        // session see at-least-this-revision consistency. The whole bulk result
        // comes from a single SpiceDB dispatch, so every inner CheckResult
        // carries the same zedToken — we just grab the first non-null one.
        // Matches the symmetric behavior of single-subject check() above.
        if (result != null) {
            for (CheckResult entry : result.asMap().values()) {
                String token = entry.zedToken();
                if (token != null) {
                    tokenTracker.recordRead(token);
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        GrantResult result = delegate.writeRelationships(updates);
        String resType = updates.isEmpty() ? null : updates.getFirst().resource().type();
        tokenTracker.recordWrite(resType, result.zedToken());
        return result;
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        RevokeResult result = delegate.deleteRelationships(updates);
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
        LookupSubjectsRequest adjusted = request.withConsistency(
                resolveConsistency(request.resource().type(), request.consistency()));
        return delegate.lookupSubjects(adjusted);
    }

    @Override
    public List<ResourceRef> lookupResources(LookupResourcesRequest request) {
        LookupResourcesRequest adjusted = request.withConsistency(
                resolveConsistency(request.resourceType(), request.consistency()));
        return delegate.lookupResources(adjusted);
    }

    @Override
    public RevokeResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                        Relation optionalRelation) {
        RevokeResult result = delegate.deleteByFilter(resource, subject, optionalRelation);
        tokenTracker.recordWrite(resource.type(), result.zedToken());
        return result;
    }

    /**
     * If the user passed minimizeLatency (the default), apply the policy for this resource type.
     * If the user explicitly passed full/atLeast/atExactSnapshot, respect it.
     */
    private Consistency resolveConsistency(String resourceType, Consistency requested) {
        Consistency effective;
        if (!(requested instanceof Consistency.MinimizeLatency)) {
            effective = requested; // explicit user choice wins
        } else {
            ReadConsistency policy = policyRegistry.resolveReadConsistency(resourceType);
            effective = tokenTracker.resolve(policy, resourceType);
        }
        // SR:req-9 — record the effective consistency on the current OTel
        // span so APM backends can filter "which reads used minimize_latency
        // vs fully_consistent" in the same trace.
        try {
            io.opentelemetry.api.trace.Span span = io.opentelemetry.api.trace.Span.current();
            if (span.getSpanContext().isValid() && effective != null) {
                span.setAttribute("authx.consistency",
                        effective.getClass().getSimpleName().toLowerCase());
            }
        } catch (Throwable ignored) {
            /* span enrichment is best-effort */
        }
        return effective;
    }
}
