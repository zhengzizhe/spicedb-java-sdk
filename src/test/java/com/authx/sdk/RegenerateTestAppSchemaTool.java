package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.SubjectType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline regenerator for {@code test-app/src/main/java/com/authx/testapp/schema/*}.
 * Mirrors {@code deploy/schema.zed}. Enable this test (remove {@code @Disabled})
 * locally, run it, then re-add the {@code @Disabled} so CI doesn't overwrite
 * the committed schema files on every run.
 *
 * <p>Keeping this as a checked-in tool (vs a throwaway script) means the
 * "one source of truth for test-app schema" lives in code review. Any schema
 * change updates this tool; re-running regenerates the Java sources in one
 * step with no drift risk.
 */
@Disabled("manual regeneration tool; remove the @Disabled to regenerate")
class RegenerateTestAppSchemaTool {

    @Test
    void regenerate() throws Exception {
        java.util.List<com.authx.sdk.model.SubjectType> user = List.of(SubjectType.of("user"));
        java.util.List<com.authx.sdk.model.SubjectType> userOrDept = List.of(
                SubjectType.of("user"),
                SubjectType.of("department", "all_members"));
        java.util.List<com.authx.sdk.model.SubjectType> userOrGroupMember = List.of(
                SubjectType.of("user"),
                SubjectType.of("group", "member"));
        java.util.List<com.authx.sdk.model.SubjectType> userOrGroupOrDept = List.of(
                SubjectType.of("user"),
                SubjectType.of("group", "member"),
                SubjectType.of("department", "all_members"));
        java.util.List<com.authx.sdk.model.SubjectType> userOrGroupOrDeptOrWildcard = List.of(
                SubjectType.of("user"),
                SubjectType.of("group", "member"),
                SubjectType.of("department", "all_members"),
                SubjectType.wildcard("user"));
        java.util.List<com.authx.sdk.model.SubjectType> wildcardOnly = List.of(SubjectType.wildcard("user"));

        com.authx.sdk.cache.SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.ofEntries(
                Map.entry("user", new SchemaCache.DefinitionCache(
                        Set.of(), Set.of(), Map.of())),
                Map.entry("department", new SchemaCache.DefinitionCache(
                        Set.of("member", "parent"),
                        Set.of("all_members"),
                        Map.of(
                                "member", user,
                                "parent", List.of(SubjectType.of("department"))))),
                Map.entry("group", new SchemaCache.DefinitionCache(
                        Set.of("member"),
                        Set.of(),
                        Map.of("member", userOrDept))),
                Map.entry("organization", new SchemaCache.DefinitionCache(
                        Set.of("admin", "member"),
                        Set.of("manage", "access"),
                        Map.of(
                                "admin", user,
                                "member", userOrDept))),
                Map.entry("space", new SchemaCache.DefinitionCache(
                        Set.of("org", "owner", "admin", "member", "viewer"),
                        Set.of("manage", "edit", "view"),
                        Map.of(
                                "org", List.of(SubjectType.of("organization")),
                                "owner", user,
                                "admin", userOrGroupMember,
                                "member", userOrGroupOrDept,
                                "viewer", userOrGroupOrDeptOrWildcard))),
                Map.entry("folder", new SchemaCache.DefinitionCache(
                        Set.of("space", "parent", "owner", "editor", "commenter", "viewer"),
                        Set.of("manage", "edit", "comment", "view", "create_child"),
                        Map.of(
                                "space", List.of(SubjectType.of("space")),
                                "parent", List.of(SubjectType.of("folder")),
                                "owner", user,
                                "editor", userOrGroupOrDept,
                                "commenter", userOrGroupOrDept,
                                "viewer", userOrGroupOrDeptOrWildcard))),
                Map.entry("document", new SchemaCache.DefinitionCache(
                        Set.of("folder", "space", "owner", "editor", "commenter", "viewer",
                                "link_viewer", "link_editor"),
                        Set.of("manage", "edit", "comment", "view", "delete", "share"),
                        Map.of(
                                "folder", List.of(SubjectType.of("folder")),
                                "space", List.of(SubjectType.of("space")),
                                "owner", user,
                                "editor", userOrGroupOrDept,
                                "commenter", userOrGroupOrDept,
                                "viewer", userOrGroupOrDeptOrWildcard,
                                "link_viewer", wildcardOnly,
                                "link_editor", wildcardOnly)))));

        // Caveats — mirrors deploy/schema.zed's `caveat ip_allowlist`.
        // Parameter types use SpiceDB type names (list<string>, string, ...)
        // which AuthxCodegen maps to Java (List<String>, String, ...) in the
        // generated IpAllowlist.java doc strings.
        cache.updateCaveats(Map.of(
                "ip_allowlist", new SchemaCache.CaveatDef(
                        "ip_allowlist",
                        new java.util.LinkedHashMap<>(Map.of(
                                "cidrs", "list<string>",
                                "client_ip", "string")),
                        "cidrs.exists(c, client_ip.startsWith(c))",
                        "IP allowlist — grants only fire when client_ip matches one of cidrs.")));

        AuthxCodegen.generate(new SchemaClient(cache),
                "test-app/src/main/java", "com.authx.testapp.schema");
    }
}
