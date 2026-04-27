package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.SubjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-ish test for {@link AuthxCodegen} that feeds it the exact
 * shape of {@code deploy/schema.zed} and verifies the emitted
 * {@code Document.java} carries the correct {@link SubjectType} metadata.
 *
 * <p>This is the regression guard for the live-SpiceDB regeneration path
 * exercised by {@link RegenerateTestAppSchemaTool}: when the schema
 * changes, updating this test pins the expected output before the
 * committed Java sources are refreshed.
 */
class AuthxCodegenRegenerationIT {

    @Test
    void regeneratesDocumentWithSubjectTypes(@TempDir Path tmp) throws Exception {
        java.util.List<com.authx.sdk.model.SubjectType> user = List.of(SubjectType.of("user"));
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
        cache.updateFromMap(Map.of(
                "document", new SchemaCache.DefinitionCache(
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
                                "link_editor", wildcardOnly))));

        com.authx.sdk.SchemaClient schema = new SchemaClient(cache);
        AuthxCodegen.generate(schema, tmp.toString(), "com.example");

        String doc = Files.readString(tmp.resolve("com/example/Document.java"));
        assertThat(doc).contains("FOLDER(\"folder\", \"folder\")");
        assertThat(doc).contains("VIEWER(\"viewer\", \"user\", \"group#member\", \"department#all_members\", \"user:*\")");
        assertThat(doc).contains("LINK_VIEWER(\"link_viewer\", \"user:*\")");
        assertThat(doc).contains("OWNER(\"owner\", \"user\")");
    }
}
