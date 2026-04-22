package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectRef;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strongly-typed chain entry point returned by
 * {@link AuthxClient#on(ResourceType)}. Knows the relation / permission
 * enum classes for its resource type, so {@link #select(String...)},
 * {@link #findBy(SubjectRef)} and friends hand back typed actions that
 * accept only the matching enums.
 *
 * <pre>
 * client.on(Document)
 *       .select(docId)
 *       .check(Document.Perm.VIEW)
 *       .by(userId);
 *
 * client.on(Document)
 *       .findByUser(userId)
 *       .limit(100)
 *       .can(Document.Perm.VIEW);
 * </pre>
 *
 * <p>This type is deliberately lightweight: it holds the shared
 * {@link ResourceFactory} (which provides transport / cache / schema
 * validation) plus the enum class metadata from {@link ResourceType}.
 * No RPCs are made until the caller reaches a terminal operation.
 */
public final class TypedResourceEntry<R extends Enum<R> & Relation.Named,
                                      P extends Enum<P> & Permission.Named> {

    private final ResourceFactory factory;
    private final ResourceType<R, P> type;

    TypedResourceEntry(ResourceFactory factory, ResourceType<R, P> type) {
        this.factory = factory;
        this.type = type;
    }

    /** The underlying resource type descriptor. */
    public ResourceType<R, P> type() { return type; }

    // ────────────────────────────────────────────────────────────────
    //  select(...) — bind to one or more resource ids
    // ────────────────────────────────────────────────────────────────

    /**
     * Bind to one or more resource ids and return a typed handle that
     * supports {@code check / grant / revoke / checkAll / who}.
     */
    public TypedHandle<R, P> select(String... ids) {
        if (ids == null || ids.length == 0) {
            throw new IllegalArgumentException("select() requires at least one id");
        }
        return new TypedHandle<>(factory, ids, type.permClass());
    }

    /** Collection overload for {@link #select(String...)}. */
    public TypedHandle<R, P> select(Collection<String> ids) {
        return select(ids.toArray(String[]::new));
    }

    // ────────────────────────────────────────────────────────────────
    //  findBy(...) — reverse lookup (lookupResources)
    // ────────────────────────────────────────────────────────────────

    /** "Which resources of this type can {@code subject} access?" */
    public TypedFinder<P> findBy(SubjectRef subject) {
        return new TypedFinder<>(factory, subject);
    }

    /** Canonical-string form of {@link #findBy(SubjectRef)} — {@code "user:alice"} etc. */
    public TypedFinder<P> findBy(String subjectRef) {
        return new TypedFinder<>(factory, SubjectRef.parse(subjectRef));
    }

    /**
     * Typed subject form: {@code client.on(Document).findBy(User, "alice")}.
     * Constructs the canonical subject ref before building the finder.
     */
    public <SR extends Enum<SR> & Relation.Named, SP extends Enum<SP> & Permission.Named>
    TypedFinder<P> findBy(ResourceType<SR, SP> subjectType, String id) {
        return findBy(subjectType.name() + ":" + id);
    }

    /**
     * Typed batch subject form:
     * {@code client.on(Document).findBy(User, List.of("alice", "bob"))}.
     * Wraps each id as {@code type:id} and dispatches as a multi-subject lookup.
     */
    public <SR extends Enum<SR> & Relation.Named, SP extends Enum<SP> & Permission.Named>
    MultiFinder<R, P> findBy(ResourceType<SR, SP> subjectType, Iterable<String> ids) {
        java.util.List<String> refs = new java.util.ArrayList<>();
        for (String id : ids) refs.add(subjectType.name() + ":" + id);
        return findBy(refs);
    }

    // ────────────────────────────────────────────────────────────────
    //  Multi-subject finder — terminal runs one lookupResources per subject
    // ────────────────────────────────────────────────────────────────

    /**
     * Multi-subject reverse lookup. Returns an intermediate that, when
     * terminated with {@code .can(Perm)}, runs one {@code LookupResources}
     * RPC per subject and returns a {@code Map<subjectRef, List<resourceId>>}.
     *
     * <pre>
     * Map&lt;String, List&lt;String&gt;&gt; perUser = client.on(Document)
     *     .findBy(SubjectRef.of("user","alice"), SubjectRef.of("user","bob"))
     *     .can(Document.Perm.EDIT);
     * </pre>
     */
    public MultiFinder<R, P> findBy(SubjectRef... subjects) {
        return new MultiFinder<>(factory, List.of(subjects));
    }

    public MultiFinder<R, P> findBy(Collection<SubjectRef> subjects) {
        return new MultiFinder<>(factory, List.copyOf(subjects));
    }

    /** Canonical-string varargs form of {@link #findBy(SubjectRef...)}. */
    public MultiFinder<R, P> findBy(String... subjectRefs) {
        var refs = new java.util.ArrayList<SubjectRef>(subjectRefs.length);
        for (String s : subjectRefs) refs.add(SubjectRef.parse(s));
        return new MultiFinder<>(factory, refs);
    }

    /** {@link Iterable} overload of {@link #findBy(String...)}. */
    public MultiFinder<R, P> findBy(Iterable<String> subjectRefs) {
        var refs = new java.util.ArrayList<SubjectRef>();
        for (String s : subjectRefs) refs.add(SubjectRef.parse(s));
        return new MultiFinder<>(factory, refs);
    }

    /**
     * Intermediate for multi-subject reverse lookup. Holds a subject list
     * and an optional {@code limit}; the terminal is {@code can(P)}.
     */
    public static final class MultiFinder<R extends Enum<R> & Relation.Named,
                                           P extends Enum<P> & Permission.Named> {
        private final ResourceFactory factory;
        private final List<SubjectRef> subjects;
        private int limit = 0;

        MultiFinder(ResourceFactory factory, List<SubjectRef> subjects) {
            this.factory = factory;
            this.subjects = subjects;
        }

        public MultiFinder<R, P> limit(int n) { this.limit = n; return this; }

        /** Run one {@code LookupResources} per subject and collect results keyed by the subject's canonical ref string. */
        public Map<String, List<String>> can(P permission) {
            var out = new LinkedHashMap<String, List<String>>(subjects.size());
            for (SubjectRef subject : subjects) {
                out.put(subject.toRefString(), new TypedFinder<P>(factory, subject).limit(limit).can(permission));
            }
            return out;
        }
    }
}
