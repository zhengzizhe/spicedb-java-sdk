package com.authx.sdk.transport;

import com.authx.sdk.model.*;

import java.util.List;

/**
 * Abstract base for transport decorators. Delegates all methods to {@link #delegate()}.
 * Subclasses override only the methods they need to enhance.
 * <p>When new methods are added to SdkTransport, they automatically pass through
 * without requiring changes to every decorator.
 */
public abstract class ForwardingTransport implements SdkTransport {

    protected abstract SdkTransport delegate();

    @Override
    public CheckResult check(CheckRequest request) {
        return delegate().check(request);
    }

    @Override
    public BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects) {
        return delegate().checkBulk(request, subjects);
    }

    @Override
    public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
        return delegate().checkBulkMulti(items, consistency);
    }

    @Override
    public WriteResult writeRelationships(List<RelationshipUpdate> updates) {
        return delegate().writeRelationships(updates);
    }

    @Override
    public WriteResult deleteRelationships(List<RelationshipUpdate> updates) {
        return delegate().deleteRelationships(updates);
    }

    @Override
    public List<Tuple> readRelationships(ResourceRef resource, Relation relation, Consistency consistency) {
        return delegate().readRelationships(resource, relation, consistency);
    }

    @Override
    public List<SubjectRef> lookupSubjects(LookupSubjectsRequest request) {
        return delegate().lookupSubjects(request);
    }

    @Override
    public List<ResourceRef> lookupResources(LookupResourcesRequest request) {
        return delegate().lookupResources(request);
    }

    @Override
    public WriteResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                        Relation optionalRelation) {
        return delegate().deleteByFilter(resource, subject, optionalRelation);
    }

    @Override
    public ExpandTree expand(ResourceRef resource, Permission permission, Consistency consistency) {
        return delegate().expand(resource, permission, consistency);
    }

    @Override
    public void close() {
        delegate().close();
    }
}
