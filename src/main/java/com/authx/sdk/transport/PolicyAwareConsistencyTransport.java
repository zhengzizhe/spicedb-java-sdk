package com.authx.sdk.transport;

import com.authx.sdk.model.*;
import com.authx.sdk.exception.TokenStoreUnavailableException;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ReadConsistency;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces SessionConsistencyTransport. Uses PolicyRegistry to resolve per-resource-type
 * ReadConsistency, and TokenTracker to map it to concrete SpiceDB Consistency.
 *
 * <p>When the user passes minimizeLatency() but the policy for this resource type says SESSION,
 * we upgrade to atLeast(lastWriteToken). Time-based bounded staleness is unsupported
 * and fails explicitly rather than silently selecting a weaker consistency.
 *
 * <p>When the user explicitly passes a non-default consistency (full(), atLeast(), atExactSnapshot()),
 * we respect it for legacy policies. A coordinated-session policy requires its
 * shared bound and rejects overrides that cannot prove the same requirement.
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
        requireCoordinatedOutcome(request.resource().type(), result);
        return result;
    }

    @Override
    public BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects) {
        CheckRequest adjusted = request.withConsistency(
                resolveConsistency(request.resource().type(), request.consistency()));
        BulkCheckResult result = delegate.checkBulk(adjusted, subjects);
        result.asMap().values().forEach(item -> requireCoordinatedOutcome(request.resource().type(), item));
        return result;
    }

    @Override
    public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
        if (items.isEmpty()) return List.of();

        // One required namespace token for this whole operation, including related resource types.
        Consistency sharedBound = items.stream().anyMatch(item -> isCoordinated(item.resource().type()))
                ? tokenTracker.resolve(ReadConsistency.coordinatedSession(), items.getFirst().resource().type()) : null;
        Map<String, Consistency> byType = new LinkedHashMap<>();
        Map<Consistency, List<IndexedBulkCheckItem>> groups = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            BulkCheckItem item = items.get(i);
            Consistency effective = byType.computeIfAbsent(item.resource().type(),
                    type -> resolveConsistency(type, consistency, sharedBound));
            groups.computeIfAbsent(effective, ignored -> new ArrayList<>())
                    .add(new IndexedBulkCheckItem(i, item));
        }

        ArrayList<CheckResult> ordered = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ordered.add(CheckResult.denied(null));
        }

        for (Map.Entry<Consistency, List<IndexedBulkCheckItem>> entry : groups.entrySet()) {
            List<BulkCheckItem> groupItems = entry.getValue().stream()
                    .map(IndexedBulkCheckItem::item)
                    .toList();
            List<CheckResult> groupResults = delegate.checkBulkMulti(groupItems, entry.getKey());
            int resultCount = Math.min(groupResults.size(), entry.getValue().size());
            for (int i = 0; i < resultCount; i++) {
                CheckResult result = groupResults.get(i);
                requireCoordinatedOutcome(entry.getValue().get(i).item().resource().type(), result);
                ordered.set(entry.getValue().get(i).index(),
                        result != null ? result : CheckResult.denied(null));
            }
        }

        return ordered;
    }

    @Override
    public WriteResult writeRelationships(List<RelationshipUpdate> updates) {
        return writeRelationships(new WriteRequest(updates));
    }

    @Override
    public WriteResult writeRelationships(WriteRequest request) {
        List<RelationshipUpdate> updates = request.updates();
        WriteResult result = tokenTracker.executeWrite(() -> delegate.writeRelationships(request));
        recordWriteForTouchedResourceTypes(updates, result.zedToken());
        return result;
    }

    @Override
    public WriteResult deleteRelationships(List<RelationshipUpdate> updates) {
        WriteResult result = tokenTracker.executeWrite(() -> delegate.deleteRelationships(updates));
        recordWriteForTouchedResourceTypes(updates, result.zedToken());
        return result;
    }

    @Override
    public List<Tuple> readRelationships(ResourceRef resource, Relation relation, Consistency consistency) {
        return delegate.readRelationships(resource, relation,
                resolveConsistency(resource.type(), consistency));
    }

    @Override
    public List<RelationshipRecord> readRelationshipDetails(ResourceRef resource, Relation relation, Consistency consistency) {
        return delegate.readRelationshipDetails(resource, relation, resolveConsistency(resource.type(), consistency));
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
    public WriteResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                        Relation optionalRelation) {
        WriteResult result = tokenTracker.executeWrite(() -> delegate.deleteByFilter(resource, subject, optionalRelation));
        tokenTracker.recordWrite(resource.type(), result.zedToken());
        return result;
    }

    /**
     * If the user passed minimizeLatency (the default), apply the policy for this resource type.
     * Legacy policies accept explicit full/atLeast/atExactSnapshot. Coordinated
     * policies accept only the default or the currently required shared bound.
     */
    private Consistency resolveConsistency(String resourceType, Consistency requested) {
        return resolveConsistency(resourceType, requested, null);
    }

    private boolean isCoordinated(String resourceType) {
        return policyRegistry.resolveReadConsistency(resourceType) instanceof ReadConsistency.CoordinatedSession;
    }

    private void requireCoordinatedOutcome(String type, CheckResult result) {
        if (isCoordinated(type) && (result == null || result.zedToken() == null || result.zedToken().isBlank())) {
            throw new TokenStoreUnavailableException("Required check has no actual snapshot token");
        }
    }

    private Consistency resolveConsistency(String resourceType, Consistency requested, Consistency sharedBound) {
        Consistency effective;
        if (isCoordinated(resourceType)) {
            effective = sharedBound == null
                    ? tokenTracker.resolve(ReadConsistency.coordinatedSession(), resourceType) : sharedBound;
            if (!(requested instanceof Consistency.MinimizeLatency) && !effective.equals(requested)) {
                throw new IllegalArgumentException("Cannot override the required coordinated-session bound");
            }
        } else if (!(requested instanceof Consistency.MinimizeLatency)) {
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

    private void recordWriteForTouchedResourceTypes(List<RelationshipUpdate> updates, String zedToken) {
        if (updates.isEmpty()) {
            return;
        }
        Set<String> resourceTypes = new LinkedHashSet<>();
        for (RelationshipUpdate update : updates) {
            resourceTypes.add(update.resource().type());
        }
        for (String resourceType : resourceTypes) {
            tokenTracker.recordWrite(resourceType, zedToken);
        }
    }

    private record IndexedBulkCheckItem(int index, BulkCheckItem item) {}
}
