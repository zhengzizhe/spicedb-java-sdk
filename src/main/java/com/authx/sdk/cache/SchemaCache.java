package com.authx.sdk.cache;

import com.authx.sdk.exception.InvalidPermissionException;
import com.authx.sdk.exception.InvalidRelationException;
import com.authx.sdk.exception.InvalidResourceException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Caches schema definitions for input validation.
 * Loaded from SpiceDB ReflectSchema on startup, refreshed periodically.
 * Validates resource types, relations, and permissions at the SDK layer
 * before sending to SpiceDB — gives clear error messages with suggestions.
 */
public class SchemaCache {

    private final AtomicReference<Map<String, DefinitionCache>> cache = new AtomicReference<>(Map.of());
    private volatile Runnable refreshCallback;
    private final java.util.concurrent.atomic.AtomicLong lastRefreshMs = new java.util.concurrent.atomic.AtomicLong(0);
    private static final long REFRESH_COOLDOWN_MS = 30_000; // 30 seconds

    /** Subject type allowed on a relation. */
    public record SubjectType(String type, String optionalRelation, boolean isWildcard) {
        /** e.g., "user", "department#all_members", "user:*" */
        public String toRefPrefix() {
            if (isWildcard) return type + ":*";
            if (optionalRelation != null && !optionalRelation.isEmpty()) return type + "#" + optionalRelation;
            return type;
        }
    }

    public record DefinitionCache(
            Set<String> relations,
            Set<String> permissions,
            Map<String, List<SubjectType>> relationSubjectTypes
    ) {
        public DefinitionCache(Set<String> relations, Set<String> permissions) {
            this(relations, permissions, Map.of());
        }
    }

    /**
     * Set the callback used to refresh schema on validation miss.
     * Called by AuthxClient.Builder during startup.
     */
    public void setRefreshCallback(Runnable callback) {
        this.refreshCallback = callback;
    }

    /**
     * Update from SpiceDB ReflectSchema response.
     */
    public void updateFromMap(Map<String, DefinitionCache> definitions) {
        cache.set(Map.copyOf(definitions));
    }

    /**
     * Validate a resource type exists in the schema.
     * On miss, attempts one rate-limited refresh before throwing.
     */
    public void validateResourceType(String resourceType) {
        var c = cache.get();
        if (c.isEmpty()) return; // no schema loaded yet — skip validation
        if (c.containsKey(resourceType)) return;

        // Unknown type — try refreshing schema once (rate-limited to 30s)
        if (tryRefresh()) {
            c = cache.get();
            if (c.containsKey(resourceType)) return; // found after refresh
        }

        String suggestion = findClosest(resourceType, c.keySet());
        String msg = "Resource type \"" + resourceType + "\" does not exist in schema.";
        if (suggestion != null) msg += " Did you mean \"" + suggestion + "\"?";
        msg += " Available: " + c.keySet();
        throw new InvalidResourceException(msg);
    }

    /**
     * Rate-limited schema refresh. Returns true if refresh was executed.
     */
    private boolean tryRefresh() {
        if (refreshCallback == null) return false;
        long now = System.currentTimeMillis();
        long last = lastRefreshMs.get();
        if (now - last < REFRESH_COOLDOWN_MS) return false;
        if (!lastRefreshMs.compareAndSet(last, now)) return false;
        try {
            refreshCallback.run();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validate a relation exists on a resource type.
     */
    public void validateRelation(String resourceType, String relation) {
        var c = cache.get();
        if (c.isEmpty()) return;
        var def = c.get(resourceType);
        if (def == null) return; // unknown type — already validated elsewhere or skip
        if (!def.relations.contains(relation)) {
            // Check if it's a permission (common mistake)
            if (def.permissions.contains(relation)) {
                throw new InvalidRelationException(
                        "\"" + relation + "\" is a permission, not a relation, on \"" + resourceType + "\"."
                                + " For grant/revoke, use a relation: " + def.relations);
            }
            String suggestion = findClosest(relation, def.relations);
            String msg = "Relation \"" + relation + "\" does not exist on \"" + resourceType + "\".";
            if (suggestion != null) msg += " Did you mean \"" + suggestion + "\"?";
            msg += " Available relations: " + def.relations;
            throw new InvalidRelationException(msg);
        }
    }

    /**
     * Validate a permission exists on a resource type.
     */
    public void validatePermission(String resourceType, String permission) {
        var c = cache.get();
        if (c.isEmpty()) return;
        var def = c.get(resourceType);
        if (def == null) return;
        if (!def.permissions.contains(permission)) {
            // Check if it's a relation (common mistake)
            if (def.relations.contains(permission)) {
                throw new InvalidPermissionException(
                        "\"" + permission + "\" is a relation, not a permission, on \"" + resourceType + "\"."
                                + " For check/who, use a permission: " + def.permissions
                                + " Hint: relation \"" + permission + "\" → maybe permission \"" + guessPermission(permission) + "\"?");
            }
            String suggestion = findClosest(permission, def.permissions);
            String msg = "Permission \"" + permission + "\" does not exist on \"" + resourceType + "\".";
            if (suggestion != null) msg += " Did you mean \"" + suggestion + "\"?";
            msg += " Available permissions: " + def.permissions;
            throw new InvalidPermissionException(msg);
        }
    }

    public boolean hasSchema() {
        return !cache.get().isEmpty();
    }

    public Set<String> getResourceTypes() {
        return cache.get().keySet();
    }

    public Set<String> getRelations(String resourceType) {
        var def = cache.get().get(resourceType);
        return def != null ? def.relations : Set.of();
    }

    public Set<String> getPermissions(String resourceType) {
        var def = cache.get().get(resourceType);
        return def != null ? def.permissions : Set.of();
    }

    public List<SubjectType> getSubjectTypes(String resourceType, String relation) {
        var def = cache.get().get(resourceType);
        if (def == null) return List.of();
        var types = def.relationSubjectTypes().get(relation);
        return types != null ? types : List.of();
    }

    /**
     * Validate that {@code subjectRef} is an allowed subject for
     * {@code resourceType.relation} according to the schema. Throws
     * {@link InvalidRelationException} with a clear message listing all
     * allowed subject shapes when the check fails.
     *
     * <p>The {@code subjectRef} is the stringly "type:id" or
     * "type:id#sub_relation" form used throughout the SDK, plus the wildcard
     * "type:*". We match it against the declared subject types on the
     * relation. Wildcards match only against declared wildcard subject types
     * (not against regular typed ones).
     *
     * <p>When the schema cache is empty (common during startup before the
     * initial load, or in in-memory clients for tests) this method becomes
     * a no-op — we cannot validate without a schema, and refusing all grants
     * during that window would break legitimate callers. Operators who want
     * strict mode can block on schema loading at startup.
     */
    public void validateSubject(String resourceType, String relation, String subjectRef) {
        var c = cache.get();
        if (c.isEmpty()) return;                   // schema not yet loaded — fail open
        var def = c.get(resourceType);
        if (def == null) return;                   // unknown resource type — let other validators flag it
        var allowed = def.relationSubjectTypes().get(relation);
        if (allowed == null || allowed.isEmpty()) {
            // Relation exists but has no registered subject types in the
            // reflected schema. This can happen when the schema reflection
            // RPC did not populate them. Fail open rather than spuriously
            // rejecting every grant.
            return;
        }

        // Parse the incoming ref. Shapes:
        //   "user:alice"               → type=user, id=alice
        //   "group:eng#member"         → type=group, id=eng, subRelation=member
        //   "user:*"                   → type=user, id=*, wildcard
        String type;
        String idPart;
        int colon = subjectRef.indexOf(':');
        if (colon < 0) {
            throw new InvalidRelationException(
                    "Invalid subject reference \"" + subjectRef + "\": expected \"type:id\" form");
        }
        type = subjectRef.substring(0, colon);
        idPart = subjectRef.substring(colon + 1);

        String subRelation = "";
        int hash = idPart.indexOf('#');
        if (hash >= 0) {
            subRelation = idPart.substring(hash + 1);
            idPart = idPart.substring(0, hash);
        }
        boolean isWildcard = "*".equals(idPart);

        for (SubjectType st : allowed) {
            if (!st.type().equals(type)) continue;
            if (isWildcard) {
                if (st.isWildcard()) return;   // match: user:* allowed by "user | user:*"
                continue;
            }
            if (st.isWildcard()) continue;     // wildcard declaration doesn't match concrete id
            String declaredRel = st.optionalRelation() == null ? "" : st.optionalRelation();
            if (declaredRel.equals(subRelation)) return;  // match
        }

        // No match — build a clear error listing the allowed shapes.
        String allowedShapes = allowed.stream()
                .map(SubjectType::toRefPrefix)
                .distinct()
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        throw new InvalidRelationException(
                resourceType + "." + relation + " does not accept subject \"" + subjectRef + "\". "
                        + "Allowed subject types: " + allowedShapes
                        + ". Check your schema, or use a different relation.");
    }

    public Map<String, List<SubjectType>> getAllSubjectTypes(String resourceType) {
        var def = cache.get().get(resourceType);
        return def != null ? def.relationSubjectTypes() : Map.of();
    }

    public boolean hasResourceType(String resourceType) {
        return cache.get().containsKey(resourceType);
    }

    /**
     * Find the closest match by edit distance.
     */
    private static String findClosest(String input, Set<String> candidates) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : candidates) {
            int dist = editDistance(input.toLowerCase(), c.toLowerCase());
            if (dist < bestDist && dist <= 3) { // max 3 edits
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    private static int editDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(dp[i - 1][j] + 1, Math.min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost));
            }
        }
        return dp[a.length()][b.length()];
    }

    private static String guessPermission(String relation) {
        // Common patterns: editor → edit, viewer → view, owner → (no obvious mapping)
        if (relation.endsWith("er")) return relation.substring(0, relation.length() - 2);
        if (relation.endsWith("or")) return relation.substring(0, relation.length() - 2);
        return relation;
    }
}
