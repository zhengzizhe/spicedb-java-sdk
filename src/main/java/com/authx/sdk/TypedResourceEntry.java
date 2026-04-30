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
 * {@link #lookupResources(SubjectRef)} and friends hand back typed actions that
 * accept only the matching enums.
 *
 * <pre>
 * client.on(Document)
 *       .select(docId)
 *       .check(Document.Perm.VIEW)
 *       .by(User, userId);
 *
 * client.on(Document)
 *       .lookupResources(User, userId)
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
        SdkRefs.requireNotEmpty(ids, "select()", "id");
        return new TypedHandle<>(factory, ids, type.permClass());
    }

    /** Collection overload for {@link #select(String...)}. */
    public TypedHandle<R, P> select(Collection<String> ids) {
        SdkRefs.requireNotEmpty(ids, "select(Collection)", "id");
        return select(ids.toArray(String[]::new));
    }

    // ────────────────────────────────────────────────────────────────
    //  lookupResources(...) — subject -> resources
    // ────────────────────────────────────────────────────────────────

    /** "Which resources of this type can {@code subject} access?" */
    public TypedFinder<P> lookupResources(SubjectRef subject) {
        return new TypedFinder<>(factory, subject);
    }

    /** Canonical-string form of {@link #lookupResources(SubjectRef)} — {@code "user:alice"} etc. */
    public TypedFinder<P> lookupResources(String subjectRef) {
        return new TypedFinder<>(factory, SdkRefs.subject(subjectRef));
    }

    /**
     * Typed subject form: {@code client.on(Document).lookupResources(User, "alice")}.
     * Constructs the canonical subject ref before building the finder.
     */
    public <SR extends Enum<SR> & Relation.Named, SP extends Enum<SP> & Permission.Named>
    TypedFinder<P> lookupResources(ResourceType<SR, SP> subjectType, String id) {
        return lookupResources(SdkRefs.typedSubject(subjectType, id));
    }

    /**
     * Typed batch subject form:
     * {@code client.on(Document).lookupResources(User, List.of("alice", "bob"))}.
     * Wraps each id as {@code type:id} and dispatches as a multi-subject lookup.
     */
    public <SR extends Enum<SR> & Relation.Named, SP extends Enum<SP> & Permission.Named>
    MultiFinder<R, P> lookupResources(ResourceType<SR, SP> subjectType, Iterable<String> ids) {
        return lookupResources(SdkRefs.typedSubjectStrings(
                subjectType, ids, "lookupResources(ResourceType, Iterable)"));
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
     *     .lookupResources(SubjectRef.of("user","alice"), SubjectRef.of("user","bob"))
     *     .can(Document.Perm.EDIT);
     * </pre>
     */
    public MultiFinder<R, P> lookupResources(SubjectRef... subjects) {
        SdkRefs.requireNotEmpty(subjects, "lookupResources(...)", "subject");
        return new MultiFinder<>(factory, List.of(subjects));
    }

    public MultiFinder<R, P> lookupResources(Collection<SubjectRef> subjects) {
        SdkRefs.requireNotEmpty(subjects, "lookupResources(Collection)", "subject");
        return new MultiFinder<>(factory, List.copyOf(subjects));
    }

    /** Canonical-string varargs form of {@link #lookupResources(SubjectRef...)}. */
    public MultiFinder<R, P> lookupResources(String... subjectRefs) {
        return new MultiFinder<>(factory, List.of(SdkRefs.subjects(subjectRefs, "lookupResources(...)")));
    }

    /** {@link Iterable} overload of {@link #lookupResources(String...)}. */
    public MultiFinder<R, P> lookupResources(Iterable<String> subjectRefs) {
        return new MultiFinder<>(factory, List.of(SdkRefs.subjects(subjectRefs, "lookupResources(Iterable)")));
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
            if (subjects == null || subjects.isEmpty()) {
                throw new IllegalArgumentException("lookupResources(...) requires at least one subject");
            }
            this.factory = factory;
            this.subjects = subjects;
        }

        public MultiFinder<R, P> limit(int n) {
            if (n < 0) {
                throw new IllegalArgumentException("limit must be >= 0");
            }
            this.limit = n;
            return this;
        }

        /** Run one {@code LookupResources} per subject and collect results keyed by the subject's canonical ref string. */
        public Map<String, List<String>> can(P permission) {
            LinkedHashMap<String, List<String>> out = new LinkedHashMap<String, List<String>>(subjects.size());
            for (SubjectRef subject : subjects) {
                out.put(subject.toRefString(), new TypedFinder<P>(factory, subject).limit(limit).can(permission));
            }
            return out;
        }
    }
}
