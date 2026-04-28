package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.SubjectRef;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        this.limit = n;
        return this;
    }

    public List<String> can(String permission) {
        return factory.lookup()
                .withPermission(permission)
                .by(subject)
                .limit(limit)
                .fetch();
    }

    public Map<String, List<String>> can(String... permissions) {
        if (permissions == null || permissions.length == 0) return Map.of();
        LinkedHashMap<String, List<String>> out = new LinkedHashMap<>(permissions.length);
        for (String permission : permissions) {
            out.put(permission, can(permission));
        }
        return out;
    }

    public Map<String, List<String>> can(Collection<String> permissions) {
        if (permissions == null || permissions.isEmpty()) return Map.of();
        LinkedHashMap<String, List<String>> out = new LinkedHashMap<>(permissions.size());
        for (String permission : permissions) {
            out.put(permission, can(permission));
        }
        return out;
    }

    public List<String> canAny(String... permissions) {
        if (permissions == null || permissions.length == 0) return List.of();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String permission : permissions) seen.addAll(can(permission));
        return List.copyOf(seen);
    }

    public List<String> canAll(String... permissions) {
        if (permissions == null || permissions.length == 0) return List.of();
        Set<String> acc = new LinkedHashSet<>(can(permissions[0]));
        for (int i = 1; i < permissions.length && !acc.isEmpty(); i++) {
            acc.retainAll(new HashSet<>(can(permissions[i])));
        }
        return List.copyOf(acc);
    }
}
