package com.authx.sdk.action;

import com.authx.sdk.transport.SdkTransport;

import java.util.concurrent.Executor;

/**
 * Entry point for "who has permission/relation on this resource?" queries.
 */
public class WhoBuilder {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String defaultSubjectType;
    private final Executor asyncExecutor;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public WhoBuilder(String resourceType, String resourceId, SdkTransport transport,
                      String defaultSubjectType, Executor asyncExecutor) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.asyncExecutor = asyncExecutor;
    }

    /** Look up subjects that have the given permission (uses LookupSubjects RPC). */
    public SubjectQuery withPermission(String permission) {
        return new SubjectQuery(resourceType, resourceId, transport, defaultSubjectType,
                asyncExecutor, permission, true);
    }

    /** Look up subjects that have the given direct relation (uses ReadRelationships RPC). */
    public SubjectQuery withRelation(String relation) {
        return new SubjectQuery(resourceType, resourceId, transport, defaultSubjectType,
                asyncExecutor, relation, false);
    }
}
