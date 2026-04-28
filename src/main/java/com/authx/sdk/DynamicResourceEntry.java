package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * String-based overload of the new business API entry point.
 *
 * <pre>
 * client.on("project").select("p-1").check("view").by("user:alice");
 * client.on("project").lookupResources("user:alice").can("view");
 * </pre>
 *
 * <p>Prefer {@link AuthxClient#on(ResourceType)} in business code when
 * generated schema descriptors are available.
 */
public final class DynamicResourceEntry extends ResourceFactory {

    DynamicResourceEntry(String resourceType,
                         SdkTransport transport,
                         Executor asyncExecutor,
                         @Nullable SchemaCache schemaCache) {
        super(resourceType, transport, asyncExecutor, schemaCache);
    }

    /** Bind to one or more resource ids using the new {@code on(...).select(...)} shape. */
    public DynamicHandle select(String... ids) {
        if (ids == null || ids.length == 0) {
            throw new IllegalArgumentException("select() requires at least one id");
        }
        return new DynamicHandle(this, ids);
    }

    /** Collection overload for {@link #select(String...)}. */
    public DynamicHandle select(Collection<String> ids) {
        return select(ids.toArray(String[]::new));
    }

    /** Lookup resources of this type that {@code subject} can access. */
    public DynamicFinder lookupResources(SubjectRef subject) {
        return new DynamicFinder(this, subject);
    }

    /** Canonical-string subject form, e.g. {@code "user:alice"}. */
    public DynamicFinder lookupResources(String subjectRef) {
        return lookupResources(SubjectRef.parse(subjectRef));
    }

    /** Typed subject helper for dynamic resource types. */
    public <SR extends Enum<SR> & Relation.Named, SP extends Enum<SP> & Permission.Named>
    DynamicFinder lookupResources(ResourceType<SR, SP> subjectType, String id) {
        return lookupResources(subjectType.name() + ":" + id);
    }

    /** Multi-subject reverse lookup. */
    public MultiFinder lookupResources(String... subjectRefs) {
        ArrayList<SubjectRef> refs = new ArrayList<>(subjectRefs.length);
        for (String s : subjectRefs) refs.add(SubjectRef.parse(s));
        return new MultiFinder(this, refs);
    }

    /** Typed multi-subject reverse lookup. */
    public <SR extends Enum<SR> & Relation.Named, SP extends Enum<SP> & Permission.Named>
    MultiFinder lookupResources(ResourceType<SR, SP> subjectType, Iterable<String> ids) {
        ArrayList<SubjectRef> refs = new ArrayList<>();
        for (String id : ids) refs.add(SubjectRef.of(subjectType.name(), id));
        return new MultiFinder(this, refs);
    }

    public static final class MultiFinder {
        private final DynamicResourceEntry entry;
        private final List<SubjectRef> subjects;
        private int limit = 0;

        MultiFinder(DynamicResourceEntry entry, List<SubjectRef> subjects) {
            this.entry = entry;
            this.subjects = List.copyOf(subjects);
        }

        public MultiFinder limit(int n) {
            this.limit = n;
            return this;
        }

        public java.util.Map<String, List<String>> can(String permission) {
            java.util.LinkedHashMap<String, List<String>> out = new java.util.LinkedHashMap<>(subjects.size());
            for (SubjectRef subject : subjects) {
                out.put(subject.toRefString(), new DynamicFinder(entry, subject).limit(limit).can(permission));
            }
            return out;
        }
    }
}
