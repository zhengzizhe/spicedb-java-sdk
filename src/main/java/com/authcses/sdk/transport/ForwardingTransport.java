package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;

import java.util.List;
import java.util.Map;

/**
 * Abstract base for transport decorators. Delegates all methods to {@link #delegate()}.
 * Subclasses override only the methods they need to enhance.
 * <p>When new methods are added to SdkTransport, they automatically pass through
 * without requiring changes to every decorator.
 */
public abstract class ForwardingTransport implements SdkTransport {

    protected abstract SdkTransport delegate();

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency) {
        return delegate().check(resourceType, resourceId, permission, subjectType, subjectId, consistency);
    }

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency, Map<String, Object> context) {
        return delegate().check(resourceType, resourceId, permission, subjectType, subjectId, consistency, context);
    }

    @Override
    public BulkCheckResult checkBulk(String resourceType, String resourceId,
                                     String permission, List<String> subjectIds, String defaultSubjectType,
                                     Consistency consistency) {
        return delegate().checkBulk(resourceType, resourceId, permission, subjectIds, defaultSubjectType, consistency);
    }

    @Override
    public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
        return delegate().checkBulkMulti(items, consistency);
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        return delegate().writeRelationships(updates);
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        return delegate().deleteRelationships(updates);
    }

    @Override
    public List<Tuple> readRelationships(String resourceType, String resourceId,
                                         String relation, Consistency consistency) {
        return delegate().readRelationships(resourceType, resourceId, relation, consistency);
    }

    @Override
    public List<String> lookupSubjects(String resourceType, String resourceId,
                                        String permission, String subjectType,
                                        Consistency consistency) {
        return delegate().lookupSubjects(resourceType, resourceId, permission, subjectType, consistency);
    }

    @Override
    public List<String> lookupSubjects(String resourceType, String resourceId,
                                        String permission, String subjectType,
                                        Consistency consistency, int limit) {
        return delegate().lookupSubjects(resourceType, resourceId, permission, subjectType, consistency, limit);
    }

    @Override
    public List<String> lookupResources(String resourceType, String permission,
                                         String subjectType, String subjectId,
                                         Consistency consistency) {
        return delegate().lookupResources(resourceType, permission, subjectType, subjectId, consistency);
    }

    @Override
    public List<String> lookupResources(String resourceType, String permission,
                                         String subjectType, String subjectId,
                                         Consistency consistency, int limit) {
        return delegate().lookupResources(resourceType, permission, subjectType, subjectId, consistency, limit);
    }

    @Override
    public RevokeResult deleteByFilter(String resourceType, String resourceId,
                                        String subjectType, String subjectId,
                                        String optionalRelation) {
        return delegate().deleteByFilter(resourceType, resourceId, subjectType, subjectId, optionalRelation);
    }

    @Override
    public ExpandTree expand(String resourceType, String resourceId,
                              String permission, Consistency consistency) {
        return delegate().expand(resourceType, resourceId, permission, consistency);
    }

    @Override
    public void close() {
        delegate().close();
    }
}
