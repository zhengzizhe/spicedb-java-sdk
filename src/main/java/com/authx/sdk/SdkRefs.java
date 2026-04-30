package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

/** Package-private conversion and fail-fast helpers for fluent SDK internals. */
final class SdkRefs {

    private SdkRefs() {}

    static void requireNotEmpty(Object[] values, String method, String valueName) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException(method + " requires at least one " + valueName);
        }
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException(method + " requires non-null " + valueName + " values");
            }
        }
    }

    static void requireNotEmpty(Collection<?> values, String method, String valueName) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(method + " requires at least one " + valueName);
        }
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException(method + " requires non-null " + valueName + " values");
            }
        }
    }

    static SubjectRef subject(String canonical) {
        return SubjectRef.parse(canonical);
    }

    static SubjectRef typedSubject(ResourceType<?, ?> subjectType, String id) {
        return SubjectRef.of(Objects.requireNonNull(subjectType, "subjectType").name(), id);
    }

    static SubjectRef typedSubject(ResourceType<?, ?> subjectType, String id, String relationOrPermission) {
        return SubjectRef.of(Objects.requireNonNull(subjectType, "subjectType").name(), id, relationOrPermission);
    }

    static SubjectRef wildcardSubject(ResourceType<?, ?> subjectType) {
        return SubjectRef.of(Objects.requireNonNull(subjectType, "subjectType").name(), "*");
    }

    static SubjectRef[] subjects(String[] canonicals, String method) {
        requireNotEmpty(canonicals, method, "subject");
        SubjectRef[] refs = new SubjectRef[canonicals.length];
        for (int i = 0; i < canonicals.length; i++) {
            refs[i] = SubjectRef.parse(canonicals[i]);
        }
        return refs;
    }

    static SubjectRef[] subjects(Iterable<String> canonicals, String method) {
        if (canonicals == null) {
            throw new IllegalArgumentException(method + " requires at least one subject");
        }
        ArrayList<SubjectRef> refs = new ArrayList<>();
        for (String canonical : canonicals) {
            refs.add(SubjectRef.parse(canonical));
        }
        requireNotEmpty(refs, method, "subject");
        return refs.toArray(SubjectRef[]::new);
    }

    static String[] typedSubjectStrings(ResourceType<?, ?> subjectType, Iterable<String> ids, String method) {
        if (ids == null) {
            throw new IllegalArgumentException(method + " requires at least one id");
        }
        String typeName = Objects.requireNonNull(subjectType, "subjectType").name();
        ArrayList<String> refs = new ArrayList<>();
        for (String id : ids) {
            refs.add(typeName + ":" + id);
        }
        requireNotEmpty(refs, method, "id");
        return refs.toArray(String[]::new);
    }

    static String typedSubjectString(ResourceType<?, ?> subjectType, String id) {
        return Objects.requireNonNull(subjectType, "subjectType").name() + ":" + id;
    }

    static String typedSubjectString(ResourceType<?, ?> subjectType, String id, String relationOrPermission) {
        return Objects.requireNonNull(subjectType, "subjectType").name() + ":" + id + "#" + relationOrPermission;
    }

    static String wildcardSubjectString(ResourceType<?, ?> subjectType) {
        return Objects.requireNonNull(subjectType, "subjectType").name() + ":*";
    }

    static String[] relationNames(Relation.Named[] relations, String method) {
        requireNotEmpty(relations, method, "relation");
        String[] names = new String[relations.length];
        for (int i = 0; i < relations.length; i++) {
            names[i] = relations[i].relationName();
        }
        return names;
    }

    static String[] permissionNames(Permission.Named[] permissions, String method) {
        requireNotEmpty(permissions, method, "permission");
        String[] names = new String[permissions.length];
        for (int i = 0; i < permissions.length; i++) {
            names[i] = permissions[i].permissionName();
        }
        return names;
    }

    static String[] permissionNames(Collection<? extends Permission.Named> permissions, String method) {
        requireNotEmpty(permissions, method, "permission");
        String[] names = new String[permissions.size()];
        int i = 0;
        for (Permission.Named permission : permissions) {
            names[i++] = permission.permissionName();
        }
        return names;
    }

    static int checkedProduct(String operation, int first, int second) {
        long product = (long) first * second;
        if (product > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(operation + " would create too many cells");
        }
        return (int) product;
    }

    static int checkedProduct(String operation, int first, int second, int third) {
        long product = (long) first * second * third;
        if (product > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(operation + " would create too many cells");
        }
        return (int) product;
    }
}
