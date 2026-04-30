package com.authx.sdk;

import com.authx.sdk.model.SubjectRef;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * String-permission reverse lookup for {@link DynamicResourceEntry}.
 */
public final class DynamicFinder {

    private final ResourceFactory factory;
    private final SubjectRef subject;
    private int limit = 0;

    DynamicFinder(ResourceFactory factory, SubjectRef subject) {
        this.factory = factory;
        this.subject = subject;
    }

    public DynamicFinder limit(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        this.limit = n;
        return this;
    }

    public List<String> can(String permission) {
        return ResourceLookupSupport.can(factory, subject, permission, limit);
    }

    public Map<String, List<String>> can(String... permissions) {
        SdkRefs.requireNotEmpty(permissions, "can(...)", "permission");
        LinkedHashMap<String, List<String>> out = new LinkedHashMap<>(permissions.length);
        for (String permission : permissions) {
            out.put(permission, can(permission));
        }
        return out;
    }

    public Map<String, List<String>> can(Collection<String> permissions) {
        SdkRefs.requireNotEmpty(permissions, "can(Collection)", "permission");
        LinkedHashMap<String, List<String>> out = new LinkedHashMap<>(permissions.size());
        for (String permission : permissions) {
            out.put(permission, can(permission));
        }
        return out;
    }

    public List<String> canAny(String... permissions) {
        return ResourceLookupSupport.canAny(factory, subject, permissions, limit, "canAny(...)");
    }

    public List<String> canAll(String... permissions) {
        return ResourceLookupSupport.canAll(factory, subject, permissions, limit, "canAll(...)");
    }
}
