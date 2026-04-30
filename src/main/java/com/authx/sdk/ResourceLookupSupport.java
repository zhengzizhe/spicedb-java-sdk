package com.authx.sdk;

import com.authx.sdk.model.LookupResourcesRequest;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared lookupResources execution helpers for typed and dynamic finders. */
final class ResourceLookupSupport {

    private ResourceLookupSupport() {}

    static List<String> can(ResourceFactory factory, SubjectRef subject, String permission, int limit) {
        LookupResourcesRequest request = new LookupResourcesRequest(
                factory.resourceType(), Permission.of(permission), subject, limit);
        return factory.transport().lookupResources(request).stream()
                .map(ResourceRef::id)
                .toList();
    }

    static List<String> canAny(ResourceFactory factory, SubjectRef subject,
                               String[] permissions, int limit, String method) {
        SdkRefs.requireNotEmpty(permissions, method, "permission");
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String permission : permissions) {
            seen.addAll(can(factory, subject, permission, 0));
        }
        return applyLimit(List.copyOf(seen), limit);
    }

    static List<String> canAll(ResourceFactory factory, SubjectRef subject,
                               String[] permissions, int limit, String method) {
        SdkRefs.requireNotEmpty(permissions, method, "permission");
        Set<String> acc = new LinkedHashSet<String>(can(factory, subject, permissions[0], 0));
        for (int i = 1; i < permissions.length && !acc.isEmpty(); i++) {
            acc.retainAll(new HashSet<String>(can(factory, subject, permissions[i], 0)));
        }
        return applyLimit(List.copyOf(acc), limit);
    }

    static String[] permissions(Collection<String> permissions, String method) {
        SdkRefs.requireNotEmpty(permissions, method, "permission");
        return permissions.toArray(String[]::new);
    }

    private static List<String> applyLimit(List<String> ids, int limit) {
        if (limit <= 0 || ids.size() <= limit) {
            return ids;
        }
        return List.copyOf(ids.subList(0, limit));
    }
}
