package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                         @Nullable SchemaCache schemaCache) {
        super(resourceType, transport, schemaCache);
    }

    /** Bind to one or more resource ids using the new {@code on(...).select(...)} shape. */
    public DynamicHandle select(String... ids) {
        SdkRefs.requireNotEmpty(ids, "select()", "id");
        return new DynamicHandle(this, ids);
    }

    /** Collection overload for {@link #select(String...)}. */
    public DynamicHandle select(Collection<String> ids) {
        SdkRefs.requireNotEmpty(ids, "select(Collection)", "id");
        return select(ids.toArray(String[]::new));
    }

    /** Lookup resources of this type that {@code subject} can access. */
    public DynamicFinder lookupResources(SubjectRef subject) {
        return new DynamicFinder(this, subject);
    }

    /** Canonical-string subject form, e.g. {@code "user:alice"}. */
    public DynamicFinder lookupResources(String subjectRef) {
        return lookupResources(SdkRefs.subject(subjectRef));
    }

    /** Typed subject helper for dynamic resource types. */
    public <SR extends Enum<SR> & Relation.Named, SP extends Enum<SP> & Permission.Named>
    DynamicFinder lookupResources(ResourceType<SR, SP> subjectType, String id) {
        return lookupResources(SdkRefs.typedSubject(subjectType, id));
    }

    /** Multi-subject reverse lookup. */
    public MultiFinder lookupResources(String... subjectRefs) {
        return new MultiFinder(this, List.of(SdkRefs.subjects(subjectRefs, "lookupResources()")));
    }

    /** Typed multi-subject reverse lookup. */
    public <SR extends Enum<SR> & Relation.Named, SP extends Enum<SP> & Permission.Named>
    MultiFinder lookupResources(ResourceType<SR, SP> subjectType, Iterable<String> ids) {
        return lookupResources(SdkRefs.typedSubjectStrings(
                subjectType, ids, "lookupResources(ResourceType, Iterable)"));
    }

    public static final class MultiFinder {
        private final DynamicResourceEntry entry;
        private final List<SubjectRef> subjects;
        private int limit = 0;

        MultiFinder(DynamicResourceEntry entry, List<SubjectRef> subjects) {
            if (subjects == null || subjects.isEmpty()) {
                throw new IllegalArgumentException("lookupResources(...) requires at least one subject");
            }
            this.entry = entry;
            this.subjects = List.copyOf(subjects);
        }

        public MultiFinder limit(int n) {
            if (n < 0) {
                throw new IllegalArgumentException("limit must be >= 0");
            }
            this.limit = n;
            return this;
        }

        public Map<String, List<String>> can(String permission) {
            LinkedHashMap<String, List<String>> out = new LinkedHashMap<>(subjects.size());
            for (SubjectRef subject : subjects) {
                out.put(subject.toRefString(), new DynamicFinder(entry, subject).limit(limit).can(permission));
            }
            return out;
        }
    }
}
