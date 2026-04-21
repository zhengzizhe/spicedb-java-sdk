package com.authx.sdk.action;

import com.authx.sdk.transport.SdkTransport;

import java.util.concurrent.Executor;

/**
 * Entry point for "who has permission/relation on this resource?" queries.
 *
 * <p>The subject type being queried must be specified up front
 * (see {@link com.authx.sdk.ResourceHandle#who(String)}) because the
 * SpiceDB {@code LookupSubjects} RPC requires an object type filter —
 * the SDK does not assume a default.
 */
public class WhoBuilder {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String subjectType;
    private final Executor asyncExecutor;

    /** Internal — use {@link com.authx.sdk.ResourceHandle#who(String)} entry point. */
    public WhoBuilder(String resourceType, String resourceId, SdkTransport transport,
                      String subjectType, Executor asyncExecutor) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.subjectType = subjectType;
        this.asyncExecutor = asyncExecutor;
    }

    /** Look up subjects that have the given permission (uses LookupSubjects RPC). */
    public SubjectQuery withPermission(String permission) {
        return new SubjectQuery(resourceType, resourceId, transport, subjectType,
                asyncExecutor, permission, true);
    }

    /** Look up subjects that have the given direct relation (uses ReadRelationships RPC). */
    public SubjectQuery withRelation(String relation) {
        return new SubjectQuery(resourceType, resourceId, transport, subjectType,
                asyncExecutor, relation, false);
    }
}
