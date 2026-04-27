package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.CaveatRef;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Unified fluent flow for writing and deleting relationships on a single
 * resource. Accumulates any mix of TOUCH (grant) and DELETE (revoke)
 * updates via {@link #grant} / {@link #to} and {@link #revoke} /
 * {@link #from}, then flushes them atomically in one
 * {@code WriteRelationships} RPC via {@link #commit()}.
 *
 * <h2>Grant-only</h2>
 * <pre>
 * auth.on(Document).select(docId)
 *     .grant(Document.Rel.VIEWER)
 *     .to(User, "alice")
 *     .to(Group, "eng", Group.Rel.MEMBER)
 *     .commit();
 * </pre>
 *
 * <h2>Revoke-only</h2>
 * <pre>
 * auth.on(Document).select(docId)
 *     .revoke(Document.Rel.VIEWER)
 *     .from(User, "alice")
 *     .commit();
 * </pre>
 *
 * <h2>Mixed — change role atomically</h2>
 * <pre>
 * auth.on(Document).select(docId)
 *     .revoke(Document.Rel.EDITOR).from(User, "alice")
 *     .grant(Document.Rel.VIEWER).to(User, "alice")
 *     .commit();
 * </pre>
 *
 * <h2>Modifier scope</h2>
 *
 * <p>{@link #withCaveat} / {@link #expiringAt} / {@link #expiringIn} only
 * apply to the most recent {@link #to} batch (TOUCH entries). They are
 * not meaningful on {@link #from} batches — calling them after
 * {@code .from(...)} throws.
 *
 * <h2>Listener hooks</h2>
 *
 * <p>{@link #commit()} returns a {@link WriteCompletion} that supports
 * chaining synchronous / asynchronous callbacks. See {@link WriteCompletion}.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>Single-use: after {@link #commit()} the flow is sealed.
 *   <li>{@code .to(...)} requires preceding {@code .grant(...)};
 *       {@code .from(...)} requires preceding {@code .revoke(...)}.
 *   <li>Calling {@code .to(...)} in DELETE mode (or vice versa) throws.
 *   <li>Empty commit throws.
 * </ul>
 *
 * <p>Not thread-safe.
 *
 * <p><b>ErrorProne note</b>: this class is marked
 * {@link CheckReturnValue @CheckReturnValue} — returned {@code WriteFlow}
 * instances must be used in a chain or assigned. Terminal accessors
 * ({@link #commit()}, {@link #commitAsync()}, {@link #pending()},
 * {@link #pendingCount()}) opt out via
 * {@link CanIgnoreReturnValue @CanIgnoreReturnValue}. This catches the
 * common bug where a chain is built but {@code .commit()} is forgotten.
 */
@CheckReturnValue
public final class WriteFlow {

    private enum Mode { GRANT, REVOKE }

    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final @Nullable SchemaCache schemaCache;

    /** Current mode — set by {@link #grant} / {@link #revoke}. */
    private @Nullable Mode currentMode;
    private @Nullable String currentRelation;

    /** Start index in {@link #pending} of the most recent {@link #to} batch. */
    private int lastBatchStart = -1;
    /** True iff the last batch was TOUCH — modifiers only apply to TOUCH batches. */
    private boolean lastBatchIsTouch = false;

    private final List<RelationshipUpdate> pending = new ArrayList<>();

    private boolean committed = false;

    WriteFlow(String resourceType,
              String resourceId,
              SdkTransport transport,
              @Nullable SchemaCache schemaCache) {
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.schemaCache = schemaCache;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Mode / relation switching
    // ──────────────────────────────────────────────────────────────────

    /** Switch to GRANT mode and set the current relation. */
    public WriteFlow grant(Relation.Named rel) {
        checkOpen();
        this.currentMode = Mode.GRANT;
        this.currentRelation = Objects.requireNonNull(rel, "rel").relationName();
        return this;
    }

    public WriteFlow grant(String relationName) {
        checkOpen();
        this.currentMode = Mode.GRANT;
        this.currentRelation = Objects.requireNonNull(relationName, "relationName");
        return this;
    }

    /** Switch to REVOKE mode and set the current relation. */
    public WriteFlow revoke(Relation.Named rel) {
        checkOpen();
        this.currentMode = Mode.REVOKE;
        this.currentRelation = Objects.requireNonNull(rel, "rel").relationName();
        return this;
    }

    public WriteFlow revoke(String relationName) {
        checkOpen();
        this.currentMode = Mode.REVOKE;
        this.currentRelation = Objects.requireNonNull(relationName, "relationName");
        return this;
    }

    // ──────────────────────────────────────────────────────────────────
    //  TOUCH batch — .to(...)
    // ──────────────────────────────────────────────────────────────────

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    WriteFlow to(ResourceType<R, P> type, String id) {
        return beginBatch(Mode.GRANT).addSubject(SubjectRef.of(type.name(), id));
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    WriteFlow to(ResourceType<R, P> type, String... ids) {
        beginBatch(Mode.GRANT);
        for (String id : ids) addSubject(SubjectRef.of(type.name(), id));
        return this;
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    WriteFlow to(ResourceType<R, P> type, Iterable<String> ids) {
        beginBatch(Mode.GRANT);
        for (String id : ids) addSubject(SubjectRef.of(type.name(), id));
        return this;
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named,
            SR extends Enum<SR> & Relation.Named>
    WriteFlow to(ResourceType<R, P> type, String id, SR subRel) {
        return beginBatch(Mode.GRANT).addSubject(
                SubjectRef.of(type.name(), id, subRel.relationName()));
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named,
            SR extends Enum<SR> & Relation.Named>
    WriteFlow to(ResourceType<R, P> type, Iterable<String> ids, SR subRel) {
        beginBatch(Mode.GRANT);
        String rel = subRel.relationName();
        for (String id : ids) addSubject(SubjectRef.of(type.name(), id, rel));
        return this;
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    WriteFlow to(ResourceType<R, P> type, String id, Permission.Named subPerm) {
        return beginBatch(Mode.GRANT).addSubject(
                SubjectRef.of(type.name(), id, subPerm.permissionName()));
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    WriteFlow toWildcard(ResourceType<R, P> type) {
        return beginBatch(Mode.GRANT).addSubject(SubjectRef.of(type.name(), "*"));
    }

    public WriteFlow to(SubjectRef subject) {
        return beginBatch(Mode.GRANT).addSubject(Objects.requireNonNull(subject, "subject"));
    }

    public WriteFlow to(SubjectRef... subjects) {
        beginBatch(Mode.GRANT);
        for (SubjectRef s : subjects) addSubject(Objects.requireNonNull(s, "subject"));
        return this;
    }

    public WriteFlow to(Collection<SubjectRef> subjects) {
        beginBatch(Mode.GRANT);
        for (SubjectRef s : subjects) addSubject(Objects.requireNonNull(s, "subject"));
        return this;
    }

    public WriteFlow to(String canonical) {
        return beginBatch(Mode.GRANT).addSubject(SubjectRef.parse(canonical));
    }

    public WriteFlow to(String... canonicals) {
        beginBatch(Mode.GRANT);
        for (String c : canonicals) addSubject(SubjectRef.parse(c));
        return this;
    }

    public WriteFlow to(Iterable<String> canonicals) {
        beginBatch(Mode.GRANT);
        for (String c : canonicals) addSubject(SubjectRef.parse(c));
        return this;
    }

    // ──────────────────────────────────────────────────────────────────
    //  DELETE batch — .from(...)
    // ──────────────────────────────────────────────────────────────────

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    WriteFlow from(ResourceType<R, P> type, String id) {
        return beginBatch(Mode.REVOKE).addSubject(SubjectRef.of(type.name(), id));
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    WriteFlow from(ResourceType<R, P> type, String... ids) {
        beginBatch(Mode.REVOKE);
        for (String id : ids) addSubject(SubjectRef.of(type.name(), id));
        return this;
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    WriteFlow from(ResourceType<R, P> type, Iterable<String> ids) {
        beginBatch(Mode.REVOKE);
        for (String id : ids) addSubject(SubjectRef.of(type.name(), id));
        return this;
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named,
            SR extends Enum<SR> & Relation.Named>
    WriteFlow from(ResourceType<R, P> type, String id, SR subRel) {
        return beginBatch(Mode.REVOKE).addSubject(
                SubjectRef.of(type.name(), id, subRel.relationName()));
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named,
            SR extends Enum<SR> & Relation.Named>
    WriteFlow from(ResourceType<R, P> type, Iterable<String> ids, SR subRel) {
        beginBatch(Mode.REVOKE);
        String rel = subRel.relationName();
        for (String id : ids) addSubject(SubjectRef.of(type.name(), id, rel));
        return this;
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    WriteFlow from(ResourceType<R, P> type, String id, Permission.Named subPerm) {
        return beginBatch(Mode.REVOKE).addSubject(
                SubjectRef.of(type.name(), id, subPerm.permissionName()));
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    WriteFlow fromWildcard(ResourceType<R, P> type) {
        return beginBatch(Mode.REVOKE).addSubject(SubjectRef.of(type.name(), "*"));
    }

    public WriteFlow from(SubjectRef subject) {
        return beginBatch(Mode.REVOKE).addSubject(Objects.requireNonNull(subject, "subject"));
    }

    public WriteFlow from(SubjectRef... subjects) {
        beginBatch(Mode.REVOKE);
        for (SubjectRef s : subjects) addSubject(Objects.requireNonNull(s, "subject"));
        return this;
    }

    public WriteFlow from(Collection<SubjectRef> subjects) {
        beginBatch(Mode.REVOKE);
        for (SubjectRef s : subjects) addSubject(Objects.requireNonNull(s, "subject"));
        return this;
    }

    public WriteFlow from(String canonical) {
        return beginBatch(Mode.REVOKE).addSubject(SubjectRef.parse(canonical));
    }

    public WriteFlow from(String... canonicals) {
        beginBatch(Mode.REVOKE);
        for (String c : canonicals) addSubject(SubjectRef.parse(c));
        return this;
    }

    public WriteFlow from(Iterable<String> canonicals) {
        beginBatch(Mode.REVOKE);
        for (String c : canonicals) addSubject(SubjectRef.parse(c));
        return this;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Modifiers — only apply to the most recent TOUCH batch
    // ──────────────────────────────────────────────────────────────────

    public WriteFlow withCaveat(String name, Map<String, Object> ctx) {
        return withCaveat(new CaveatRef(name, ctx));
    }

    public WriteFlow withCaveat(CaveatRef ref) {
        checkOpen();
        requireActiveTouchBatch("withCaveat");
        Objects.requireNonNull(ref, "ref");
        rewriteBatch(u -> new RelationshipUpdate(
                u.operation(), u.resource(), u.relation(), u.subject(),
                ref, u.expiresAt()));
        return this;
    }

    public WriteFlow expiringAt(Instant when) {
        checkOpen();
        requireActiveTouchBatch("expiringAt");
        Objects.requireNonNull(when, "when");
        rewriteBatch(u -> new RelationshipUpdate(
                u.operation(), u.resource(), u.relation(), u.subject(),
                u.caveat(), when));
        return this;
    }

    public WriteFlow expiringIn(Duration d) {
        Objects.requireNonNull(d, "d");
        return expiringAt(Instant.now().plus(d));
    }

    // ──────────────────────────────────────────────────────────────────
    //  Termination
    // ──────────────────────────────────────────────────────────────────

    /**
     * Flush all accumulated updates (TOUCH + DELETE mixed) in one
     * {@code WriteRelationships} RPC. Returns a {@link WriteCompletion}
     * for optional listener chaining. SpiceDB applies all updates
     * atomically.
     */
    @CanIgnoreReturnValue
    public WriteCompletion commit() {
        checkOpen();
        if (pending.isEmpty()) {
            throw new IllegalStateException(
                    "WriteFlow.commit() called with no pending updates — "
                    + "call .to(...) or .from(...) at least once before .commit()");
        }
        committed = true;
        validateSchemaFailFast();
        return new WriteCompletion(
                transport.writeRelationships(List.copyOf(pending)),
                pending.size());
    }

    @CanIgnoreReturnValue
    public CompletableFuture<WriteCompletion> commitAsync() {
        checkOpen();
        if (pending.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "WriteFlow.commitAsync() called with no pending updates"));
        }
        committed = true;
        try {
            validateSchemaFailFast();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        List<RelationshipUpdate> snapshot = List.copyOf(pending);
        int count = snapshot.size();
        return CompletableFuture.supplyAsync(() ->
                new WriteCompletion(transport.writeRelationships(snapshot), count));
    }

    // ──────────────────────────────────────────────────────────────────
    //  Introspection
    // ──────────────────────────────────────────────────────────────────

    @CanIgnoreReturnValue
    public List<RelationshipUpdate> pending() {
        return List.copyOf(pending);
    }

    @CanIgnoreReturnValue
    public int pendingCount() {
        return pending.size();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Internals
    // ──────────────────────────────────────────────────────────────────

    @CanIgnoreReturnValue
    private WriteFlow beginBatch(Mode expected) {
        checkOpen();
        if (currentMode == null || currentRelation == null) {
            throw new IllegalStateException(
                    expected == Mode.GRANT
                            ? "WriteFlow: call .grant(...) before .to(...)"
                            : "WriteFlow: call .revoke(...) before .from(...)");
        }
        if (currentMode != expected) {
            throw new IllegalStateException(
                    expected == Mode.GRANT
                            ? "WriteFlow: .to(...) requires GRANT mode — current mode is REVOKE; call .grant(...) first"
                            : "WriteFlow: .from(...) requires REVOKE mode — current mode is GRANT; call .revoke(...) first");
        }
        lastBatchStart = pending.size();
        lastBatchIsTouch = (expected == Mode.GRANT);
        return this;
    }

    @CanIgnoreReturnValue
    private WriteFlow addSubject(SubjectRef subject) {
        Operation op = (currentMode == Mode.GRANT) ? Operation.TOUCH : Operation.DELETE;
        pending.add(new RelationshipUpdate(
                op,
                ResourceRef.of(resourceType, resourceId),
                Relation.of(currentRelation),
                subject,
                null,
                null));
        return this;
    }

    private void rewriteBatch(UnaryOperator<RelationshipUpdate> rewrite) {
        for (int i = lastBatchStart; i < pending.size(); i++) {
            pending.set(i, rewrite.apply(pending.get(i)));
        }
    }

    private void requireActiveTouchBatch(String method) {
        if (lastBatchStart < 0 || lastBatchStart >= pending.size()) {
            throw new IllegalStateException(
                    "WriteFlow." + method + "() requires a preceding .to(...) call");
        }
        if (!lastBatchIsTouch) {
            throw new IllegalStateException(
                    "WriteFlow." + method + "() applies only to .to(...) batches (TOUCH), "
                            + "not .from(...) batches (DELETE)");
        }
    }

    private void checkOpen() {
        if (committed) {
            throw new IllegalStateException(
                    "WriteFlow already committed — create a new flow");
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
