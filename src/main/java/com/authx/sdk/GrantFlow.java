package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.CaveatRef;
import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Fluent flow for granting relationships. Accumulates (relation, subject)
 * pairs via {@link #grant(Relation.Named)} / {@link #to} calls and writes
 * them atomically on {@link #commit()}.
 *
 * <p>One {@code commit()} maps to one {@code WriteRelationships} RPC, so
 * all accumulated updates land in a single SpiceDB transaction.
 *
 * <h2>Basic usage</h2>
 *
 * <h3>Single relation, many subjects</h3>
 * <pre>
 * auth.on(Document).select(docId)
 *     .grantFlow(Document.Rel.VIEWER)
 *     .to(User, "alice")
 *     .to(User, "bob", "carol")
 *     .to(Group, "eng", Group.Rel.MEMBER)
 *     .commit();
 * </pre>
 *
 * <h3>Multiple (relation, subject) pairs</h3>
 * <pre>
 * auth.on(Document).select(docId)
 *     .grantFlow(Document.Rel.VIEWER).to(User, "alice")
 *     .grant(Document.Rel.EDITOR).to(User, "bob")
 *     .grant(Document.Rel.ADMIN).to(Group, "eng", Group.Rel.MEMBER)
 *     .commit();
 * </pre>
 *
 * <h2>Modifier scope</h2>
 *
 * <p>{@link #withCaveat} / {@link #expiringIn} / {@link #expiringAt}
 * <b>only affect the most recently added batch of subjects</b> (the last
 * {@code .to(...)} call). They do NOT persist to subsequent {@code .to()}s.
 *
 * <pre>
 * .to(User, "alice")                         // no caveat, no expiry
 * .to(User, "bob", "carol")
 *     .withCaveat("ip_check", ctx)           // applies to bob, carol only
 *     .expiringIn(Duration.ofDays(7))
 * .to(User, "dave")                          // clean again, no caveat/expiry
 * </pre>
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 *   <li>A {@code GrantFlow} is single-use: after {@link #commit()} it is
 *       sealed and any further method call throws {@link IllegalStateException}.
 *   <li>Calling {@link #to} before {@link #grant} throws — the flow has no
 *       current relation.
 *   <li>Calling a modifier ({@link #withCaveat}, {@link #expiringIn}, etc.)
 *       before any {@link #to} throws.
 *   <li>Calling {@link #commit} with no pending updates throws.
 * </ul>
 *
 * <p>This class is <b>not</b> thread-safe — each flow is expected to be
 * built within a single request / method scope.
 */
public final class GrantFlow {

    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final @Nullable SchemaCache schemaCache;

    /** The relation bound by the most recent {@link #grant(Relation.Named)}. */
    private @Nullable String currentRelation;

    /**
     * Index into {@link #pending} where the most recent {@link #to} batch
     * started. Modifier methods ({@link #withCaveat}, etc.) rewrite entries
     * in the range {@code [lastBatchStart, pending.size())}. {@code -1}
     * before any {@code to()} call.
     */
    private int lastBatchStart = -1;

    private final List<RelationshipUpdate> pending = new ArrayList<>();

    /** Sync listeners — fire on the calling thread before {@link #commit()} returns. */
    private List<Consumer<GrantResult>> syncListeners;

    /** Async listeners — dispatched to their paired executor after the write returns. */
    private List<AsyncListener<GrantResult>> asyncListeners;

    private boolean committed = false;

    private record AsyncListener<T>(Consumer<T> callback, Executor executor) {}

    GrantFlow(String resourceType,
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

    /**
     * Set the current relation. Subsequent {@link #to} calls accumulate
     * under this relation until the next {@code grant(...)}.
     */
    public GrantFlow grant(Relation.Named rel) {
        checkOpen();
        this.currentRelation = Objects.requireNonNull(rel, "rel").relationName();
        return this;
    }

    /** String form of {@link #grant(Relation.Named)}. */
    public GrantFlow grant(String relationName) {
        checkOpen();
        this.currentRelation = Objects.requireNonNull(relationName, "relationName");
        return this;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Subject accumulation — typed path
    // ──────────────────────────────────────────────────────────────────

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    GrantFlow to(ResourceType<R, P> type, String id) {
        return beginBatch().addSubject(SubjectRef.of(type.name(), id));
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    GrantFlow to(ResourceType<R, P> type, String... ids) {
        beginBatch();
        for (String id : ids) addSubject(SubjectRef.of(type.name(), id));
        return this;
    }

    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    GrantFlow to(ResourceType<R, P> type, Iterable<String> ids) {
        beginBatch();
        for (String id : ids) addSubject(SubjectRef.of(type.name(), id));
        return this;
    }

    /**
     * Typed sub-relation: {@code .to(Group, "eng", Group.Rel.MEMBER)} →
     * {@code group:eng#member}.
     */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named,
            SR extends Enum<SR> & Relation.Named>
    GrantFlow to(ResourceType<R, P> type, String id, SR subRel) {
        return beginBatch().addSubject(
                SubjectRef.of(type.name(), id, subRel.relationName()));
    }

    /**
     * Typed sub-relation batch: {@code .to(Group, groupIds, Group.Rel.MEMBER)}
     * produces one userset per id, all sharing the same sub-relation.
     */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named,
            SR extends Enum<SR> & Relation.Named>
    GrantFlow to(ResourceType<R, P> type, Iterable<String> ids, SR subRel) {
        beginBatch();
        String rel = subRel.relationName();
        for (String id : ids) addSubject(SubjectRef.of(type.name(), id, rel));
        return this;
    }

    /**
     * Typed sub-permission as sub-relation. SpiceDB wire-level treats a
     * permission on a subject resource identically to a relation there —
     * e.g. {@code department:hq#all_members}.
     *
     * <p>Uses {@link Permission.Named} directly (not a bounded enum) to
     * avoid type-erasure conflict with the {@code SR} overload above.
     */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    GrantFlow to(ResourceType<R, P> type, String id, Permission.Named subPerm) {
        return beginBatch().addSubject(
                SubjectRef.of(type.name(), id, subPerm.permissionName()));
    }

    /** Wildcard subject: {@code .toWildcard(User)} → {@code user:*}. */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    GrantFlow toWildcard(ResourceType<R, P> type) {
        return beginBatch().addSubject(SubjectRef.of(type.name(), "*"));
    }

    // ──────────────────────────────────────────────────────────────────
    //  Subject accumulation — untyped path
    // ──────────────────────────────────────────────────────────────────

    public GrantFlow to(SubjectRef subject) {
        return beginBatch().addSubject(Objects.requireNonNull(subject, "subject"));
    }

    public GrantFlow to(SubjectRef... subjects) {
        beginBatch();
        for (SubjectRef s : subjects) addSubject(Objects.requireNonNull(s, "subject"));
        return this;
    }

    public GrantFlow to(Collection<SubjectRef> subjects) {
        beginBatch();
        for (SubjectRef s : subjects) addSubject(Objects.requireNonNull(s, "subject"));
        return this;
    }

    /** Canonical string: {@code "user:alice"}, {@code "group:eng#member"}. */
    public GrantFlow to(String canonical) {
        return beginBatch().addSubject(SubjectRef.parse(canonical));
    }

    public GrantFlow to(String... canonicals) {
        beginBatch();
        for (String c : canonicals) addSubject(SubjectRef.parse(c));
        return this;
    }

    public GrantFlow to(Iterable<String> canonicals) {
        beginBatch();
        for (String c : canonicals) addSubject(SubjectRef.parse(c));
        return this;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Modifiers — apply to the most recent batch only
    // ──────────────────────────────────────────────────────────────────

    /**
     * Attach a caveat to the subjects added by the most recent {@link #to}
     * call. Overwrites any prior caveat on that same batch.
     *
     * @throws IllegalStateException if called before any {@link #to}
     */
    public GrantFlow withCaveat(String name, Map<String, Object> ctx) {
        return withCaveat(new CaveatRef(name, ctx));
    }

    public GrantFlow withCaveat(CaveatRef ref) {
        checkOpen();
        requireActiveBatch("withCaveat");
        Objects.requireNonNull(ref, "ref");
        rewriteBatch(u -> new RelationshipUpdate(
                u.operation(), u.resource(), u.relation(), u.subject(),
                ref, u.expiresAt()));
        return this;
    }

    /**
     * Set an absolute expiration for the most recent batch. Overwrites any
     * prior expiration on the same batch.
     */
    public GrantFlow expiringAt(Instant when) {
        checkOpen();
        requireActiveBatch("expiringAt");
        Objects.requireNonNull(when, "when");
        rewriteBatch(u -> new RelationshipUpdate(
                u.operation(), u.resource(), u.relation(), u.subject(),
                u.caveat(), when));
        return this;
    }

    /** Relative form of {@link #expiringAt}. Computes {@code now() + d}. */
    public GrantFlow expiringIn(Duration d) {
        Objects.requireNonNull(d, "d");
        return expiringAt(Instant.now().plus(d));
    }

    // ──────────────────────────────────────────────────────────────────
    //  Listeners (fire after commit succeeds)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Register a synchronous callback that runs on the calling thread
     * <b>after</b> the write completes and <b>before</b> {@link #commit()}
     * returns. Multiple listeners fire in registration order. If a
     * listener throws, remaining sync listeners do not fire and the
     * exception propagates to the caller.
     *
     * <p>Listeners do not fire if the write itself fails — callers see
     * the transport exception instead.
     */
    public GrantFlow listener(Consumer<GrantResult> callback) {
        checkOpen();
        Objects.requireNonNull(callback, "callback");
        if (syncListeners == null) syncListeners = new ArrayList<>(2);
        syncListeners.add(callback);
        return this;
    }

    /**
     * Register an asynchronous callback dispatched to {@code executor}
     * after the write completes. {@link #commit()} returns without
     * waiting. Callback exceptions are caught, logged at WARNING under
     * logger {@code com.authx.sdk.GrantFlow}, and swallowed — they do
     * not affect the write outcome or other listeners.
     */
    public GrantFlow listenerAsync(Consumer<GrantResult> callback, Executor executor) {
        checkOpen();
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(executor, "executor");
        if (asyncListeners == null) asyncListeners = new ArrayList<>(2);
        asyncListeners.add(new AsyncListener<>(callback, executor));
        return this;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Termination
    // ──────────────────────────────────────────────────────────────────

    /**
     * Flush all accumulated updates in one {@code WriteRelationships} RPC.
     * After this call the flow is sealed and any further method call throws.
     *
     * <p>Listeners registered via {@link #listener} / {@link #listenerAsync}
     * fire after the write returns successfully (sync listeners first, on
     * the calling thread; async ones are dispatched to their executors).
     *
     * @throws IllegalStateException if the flow is already committed or
     *         has no pending updates
     */
    public GrantResult commit() {
        checkOpen();
        if (pending.isEmpty()) {
            throw new IllegalStateException(
                    "GrantFlow.commit() called with no pending updates — "
                    + "call .to(...) at least once before .commit()");
        }
        committed = true;
        validateSchemaFailFast();
        GrantResult result = transport.writeRelationships(List.copyOf(pending));
        fireListeners(result);
        return result;
    }

    /** Async variant of {@link #commit()}. */
    public CompletableFuture<GrantResult> commitAsync() {
        checkOpen();
        if (pending.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "GrantFlow.commitAsync() called with no pending updates"));
        }
        committed = true;
        try {
            validateSchemaFailFast();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        List<RelationshipUpdate> snapshot = List.copyOf(pending);
        return CompletableFuture.supplyAsync(() -> {
            GrantResult result = transport.writeRelationships(snapshot);
            fireListeners(result);
            return result;
        });
    }

    /**
     * Read-only view of the accumulated updates. Useful for merging into a
     * larger batch via {@link CrossResourceBatchBuilder} or for debugging.
     * Callable at any point (before or after commit).
     */
    public List<RelationshipUpdate> pending() {
        return List.copyOf(pending);
    }

    /** Number of updates accumulated so far. */
    public int pendingCount() {
        return pending.size();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Internals
    // ──────────────────────────────────────────────────────────────────

    /**
     * Start a new batch: validates state and records the pending-list
     * index where this batch begins. Called by every {@code to(...)}
     * overload before adding its subjects.
     */
    private GrantFlow beginBatch() {
        checkOpen();
        if (currentRelation == null) {
            throw new IllegalStateException(
                    "GrantFlow: call .grant(...) before .to(...) — no current relation");
        }
        lastBatchStart = pending.size();
        return this;
    }

    private GrantFlow addSubject(SubjectRef subject) {
        pending.add(new RelationshipUpdate(
                Operation.TOUCH,
                ResourceRef.of(resourceType, resourceId),
                Relation.of(currentRelation),
                subject,
                null,
                null));
        return this;
    }

    private void rewriteBatch(java.util.function.UnaryOperator<RelationshipUpdate> rewrite) {
        for (int i = lastBatchStart; i < pending.size(); i++) {
            pending.set(i, rewrite.apply(pending.get(i)));
        }
    }

    private void requireActiveBatch(String method) {
        if (lastBatchStart < 0 || lastBatchStart >= pending.size()) {
            throw new IllegalStateException(
                    "GrantFlow." + method + "() requires a preceding .to(...) call");
        }
    }

    private void checkOpen() {
        if (committed) {
            throw new IllegalStateException(
                    "GrantFlow already committed — create a new flow");
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

    private void fireListeners(GrantResult result) {
        if (syncListeners != null) {
            // First throw propagates; remaining sync listeners do not fire.
            // Matches GrantCompletion semantics.
            for (Consumer<GrantResult> l : syncListeners) {
                l.accept(result);
            }
        }
        if (asyncListeners != null) {
            for (AsyncListener<GrantResult> al : asyncListeners) {
                al.executor().execute(() -> {
                    try {
                        al.callback().accept(result);
                    } catch (Throwable t) {
                        System.getLogger(GrantFlow.class.getName()).log(
                                System.Logger.Level.WARNING,
                                "GrantFlow async listener threw — swallowed", t);
                    }
                });
            }
        }
    }
}
