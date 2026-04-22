package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.RevokeResult;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Fluent flow for revoking relationships. Mirrors {@link GrantFlow} but
 * emits {@code DELETE} updates and terminates with {@link #commit()} that
 * issues a single {@code DeleteRelationships} RPC.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * auth.on(Document).select(docId)
 *     .revokeFlow(Document.Rel.VIEWER)
 *     .from(User, "alice")
 *     .from(User, "bob", "carol")
 *     .revoke(Document.Rel.EDITOR)
 *     .from(Group, "eng", Group.Rel.MEMBER)
 *     .commit();
 * </pre>
 *
 * <h2>Notes</h2>
 *
 * <ul>
 *   <li>No {@code withCaveat} — revoke is a filter-based delete, caveat is
 *       not part of the identity of the relationship being removed.
 *   <li>No {@code expiring*} — revoke happens at commit time; tuple-level
 *       expiration only makes sense at write time.
 *   <li>Same single-use lifecycle as {@link GrantFlow}.
 * </ul>
 *
 * <p>Not thread-safe.
 */
public final class RevokeFlow {

    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final @Nullable SchemaCache schemaCache;

    private @Nullable String currentRelation;
    private final List<RelationshipUpdate> pending = new ArrayList<>();
    private boolean committed = false;

    RevokeFlow(String resourceType,
               String resourceId,
               SdkTransport transport,
               @Nullable SchemaCache schemaCache) {
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.schemaCache = schemaCache;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Relation switching
    // ──────────────────────────────────────────────────────────────────

    public RevokeFlow revoke(Relation.Named rel) {
        checkOpen();
        this.currentRelation = Objects.requireNonNull(rel, "rel").relationName();
        return this;
    }

    public RevokeFlow revoke(String relationName) {
        checkOpen();
        this.currentRelation = Objects.requireNonNull(relationName, "relationName");
        return this;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Subject accumulation — typed path
    // ──────────────────────────────────────────────────────────────────

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    RevokeFlow from(ResourceType<R, P> type, String id) {
        return addSubject(SubjectRef.of(type.name(), id));
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    RevokeFlow from(ResourceType<R, P> type, String... ids) {
        checkRelationSet();
        for (String id : ids) addSubjectUnchecked(SubjectRef.of(type.name(), id));
        return this;
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    RevokeFlow from(ResourceType<R, P> type, Iterable<String> ids) {
        checkRelationSet();
        for (String id : ids) addSubjectUnchecked(SubjectRef.of(type.name(), id));
        return this;
    }

    /** Typed sub-relation: {@code .from(Group, "eng", Group.Rel.MEMBER)}. */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named,
            SR extends Enum<SR> & Relation.Named>
    RevokeFlow from(ResourceType<R, P> type, String id, SR subRel) {
        return addSubject(SubjectRef.of(type.name(), id, subRel.relationName()));
    }

    /** Typed sub-relation batch form. */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named,
            SR extends Enum<SR> & Relation.Named>
    RevokeFlow from(ResourceType<R, P> type, Iterable<String> ids, SR subRel) {
        checkRelationSet();
        String rel = subRel.relationName();
        for (String id : ids) addSubjectUnchecked(SubjectRef.of(type.name(), id, rel));
        return this;
    }

    /** Typed sub-permission as sub-relation. */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    RevokeFlow from(ResourceType<R, P> type, String id, Permission.Named subPerm) {
        return addSubject(SubjectRef.of(type.name(), id, subPerm.permissionName()));
    }

    /** Wildcard subject: {@code .fromWildcard(User)} → {@code user:*}. */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    RevokeFlow fromWildcard(ResourceType<R, P> type) {
        return addSubject(SubjectRef.of(type.name(), "*"));
    }

    // ──────────────────────────────────────────────────────────────────
    //  Subject accumulation — untyped path
    // ──────────────────────────────────────────────────────────────────

    public RevokeFlow from(SubjectRef subject) {
        return addSubject(Objects.requireNonNull(subject, "subject"));
    }

    public RevokeFlow from(SubjectRef... subjects) {
        checkRelationSet();
        for (SubjectRef s : subjects) addSubjectUnchecked(Objects.requireNonNull(s, "subject"));
        return this;
    }

    public RevokeFlow from(Collection<SubjectRef> subjects) {
        checkRelationSet();
        for (SubjectRef s : subjects) addSubjectUnchecked(Objects.requireNonNull(s, "subject"));
        return this;
    }

    public RevokeFlow from(String canonical) {
        return addSubject(SubjectRef.parse(canonical));
    }

    public RevokeFlow from(String... canonicals) {
        checkRelationSet();
        for (String c : canonicals) addSubjectUnchecked(SubjectRef.parse(c));
        return this;
    }

    public RevokeFlow from(Iterable<String> canonicals) {
        checkRelationSet();
        for (String c : canonicals) addSubjectUnchecked(SubjectRef.parse(c));
        return this;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Termination — returns RevokeCompletion for chainable listeners
    // ──────────────────────────────────────────────────────────────────

    public RevokeCompletion commit() {
        checkOpen();
        if (pending.isEmpty()) {
            throw new IllegalStateException(
                    "RevokeFlow.commit() called with no pending updates — "
                    + "call .from(...) at least once before .commit()");
        }
        committed = true;
        validateSchemaFailFast();
        RevokeResult result = transport.deleteRelationships(List.copyOf(pending));
        return new RevokeCompletion(result);
    }

    public CompletableFuture<RevokeCompletion> commitAsync() {
        checkOpen();
        if (pending.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "RevokeFlow.commitAsync() called with no pending updates"));
        }
        committed = true;
        try {
            validateSchemaFailFast();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        List<RelationshipUpdate> snapshot = List.copyOf(pending);
        return CompletableFuture.supplyAsync(() ->
                new RevokeCompletion(transport.deleteRelationships(snapshot)));
    }

    public List<RelationshipUpdate> pending() {
        return List.copyOf(pending);
    }

    public int pendingCount() {
        return pending.size();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Internals
    // ──────────────────────────────────────────────────────────────────

    private RevokeFlow addSubject(SubjectRef subject) {
        checkRelationSet();
        return addSubjectUnchecked(subject);
    }

    private RevokeFlow addSubjectUnchecked(SubjectRef subject) {
        pending.add(new RelationshipUpdate(
                Operation.DELETE,
                ResourceRef.of(resourceType, resourceId),
                Relation.of(currentRelation),
                subject,
                null,
                null));
        return this;
    }

    private void checkRelationSet() {
        checkOpen();
        if (currentRelation == null) {
            throw new IllegalStateException(
                    "RevokeFlow: call .revoke(...) before .from(...) — no current relation");
        }
    }

    private void checkOpen() {
        if (committed) {
            throw new IllegalStateException(
                    "RevokeFlow already committed — create a new flow");
        }
    }

    private void validateSchemaFailFast() {
        if (schemaCache == null) return;
        for (RelationshipUpdate u : pending) {
            schemaCache.validateSubject(
                    u.resource().type(),
                    u.relation().name(),
                    u.subject().toRefString());
        }
    }
}
