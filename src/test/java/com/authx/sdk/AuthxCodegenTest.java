package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.SubjectType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthxCodegenTest {

    @Test
    void emitsRelEnumWithSubjectTypesVarargs() {
        String code = AuthxCodegen.emitTypeClass(
                "document",
                Set.of("folder", "viewer"),
                Set.of("view"),
                Map.of(
                        "folder", List.of(SubjectType.of("folder")),
                        "viewer", List.of(
                                SubjectType.of("user"),
                                SubjectType.of("group", "member"),
                                SubjectType.wildcard("user"))),
                "com.example.perm");

        assertThat(code).contains("package com.example.perm;");
        assertThat(code).contains("import com.authx.sdk.model.SubjectType;");
        assertThat(code).contains("FOLDER(\"folder\", \"folder\")");
        assertThat(code).contains("VIEWER(\"viewer\", \"user\", \"group#member\", \"user:*\")");
        assertThat(code).contains("Rel(String v, String... sts)");
        assertThat(code).contains("Arrays.stream(sts).map(SubjectType::parse).toList()");
        assertThat(code).contains("public List<SubjectType> subjectTypes()");
        assertThat(code).contains("VIEW(\"view\")");
        // TYPE constant + ResourceType import dropped — descriptors live in Schema.java now (T014/T017)
        assertThat(code).doesNotContain("public static final ResourceType<Rel, Perm> TYPE");
        assertThat(code).doesNotContain("ResourceType.of(\"document\"");
        assertThat(code).doesNotContain("import com.authx.sdk.ResourceType;");
        assertThat(code).contains("public enum Rel implements Relation.Named");
        assertThat(code).contains("public enum Perm implements Permission.Named");
    }

    @Test
    void emitsSchemaFileWithDescriptorAndProxies() {
        String code = AuthxCodegen.emitSchema(
                "com.example.perm",
                Map.of(
                        "document", Set.of("viewer", "editor"),
                        "user",     Set.of()),
                Map.of(
                        "document", Set.of("view", "edit"),
                        "user",     Set.of()));

        // Package + imports
        assertThat(code).contains("package com.example.perm;");
        assertThat(code).contains("import com.authx.sdk.PermissionProxy;");
        assertThat(code).contains("import com.authx.sdk.ResourceType;");

        // Descriptor class
        assertThat(code).contains(
                "public static final class DocumentDescriptor\n"
              + "            extends ResourceType<com.example.perm.Document.Rel, com.example.perm.Document.Perm>");
        assertThat(code).contains(
                "super(\"document\", com.example.perm.Document.Rel.class, com.example.perm.Document.Perm.class);");

        // Rel proxy — FQN enum references (NPE guard)
        assertThat(code).contains(
                "public final com.example.perm.Document.Rel VIEWER = com.example.perm.Document.Rel.VIEWER;");
        assertThat(code).contains(
                "public final com.example.perm.Document.Rel EDITOR = com.example.perm.Document.Rel.EDITOR;");

        // Perm proxy implements PermissionProxy
        assertThat(code).contains(
                "public static final class DocumentPermProxy implements PermissionProxy<com.example.perm.Document.Perm>");
        assertThat(code).contains("return com.example.perm.Document.Perm.class;");

        // Descriptor field
        assertThat(code).contains(
                "public static final DocumentDescriptor Document = new DocumentDescriptor();");

        // User has empty proxies but still has a Descriptor field
        assertThat(code).contains("public static final UserDescriptor User = new UserDescriptor();");
        // Empty rel proxy: class declaration + its terminating brace, with nothing
        // between them besides the opening brace.
        assertThat(code).containsSubsequence(
                "public static final class UserRelProxy {",
                "}");
        // Empty perm proxy still has the enumClass() override
        assertThat(code).contains(
                "public static final class UserPermProxy implements PermissionProxy<com.example.perm.User.Perm>");
    }

    @Test
    void emitsResourceTypesConstants() {
        String code = AuthxCodegen.emitResourceTypes(
                "com.example.perm",
                Set.of("document", "folder", "user"));
        assertThat(code).contains("public static final String DOCUMENT = \"document\";");
        assertThat(code).contains("public static final String FOLDER = \"folder\";");
        assertThat(code).contains("public static final String USER = \"user\";");
    }

    @Test
    void emitsCaveatClass() {
        String code = AuthxCodegen.emitCaveatClass(
                "ip_allowlist",
                Map.of("cidrs", "list<string>", "client_ip", "string"),
                "client_ip in cidrs",
                "IP allow-list",
                "com.example.perm");
        assertThat(code).contains("public static final String NAME = \"ip_allowlist\";");
        assertThat(code).contains("public static final String CIDRS = \"cidrs\";");
        assertThat(code).contains("public static final String CLIENT_IP = \"client_ip\";");
        assertThat(code).contains("public static CaveatRef ref(Object... keyValues)");
        assertThat(code).contains("public static Map<String, Object> context(Object... keyValues)");
    }

    @Test
    void endToEndFromFakeSchema(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        com.authx.sdk.cache.SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of(
                "document", new SchemaCache.DefinitionCache(
                        Set.of("folder", "viewer"),
                        Set.of("view"),
                        Map.of(
                                "folder", List.of(SubjectType.of("folder")),
                                "viewer", List.of(
                                        SubjectType.of("user"),
                                        SubjectType.wildcard("user"))))));
        cache.updateCaveats(Map.of(
                "ip_allowlist", new SchemaCache.CaveatDef(
                        "ip_allowlist",
                        Map.of("cidrs", "list<string>"),
                        "client_ip in cidrs",
                        "")));
        com.authx.sdk.SchemaClient schema = new SchemaClient(cache);

        AuthxCodegen.generate(schema, tmp.toString(), "com.example.perm");

        Path docFile     = tmp.resolve("com/example/perm/Document.java");
        Path rtFile      = tmp.resolve("com/example/perm/ResourceTypes.java");
        Path schemaFile  = tmp.resolve("com/example/perm/Schema.java");
        Path ipFile      = tmp.resolve("com/example/perm/IpAllowlist.java");
        Path caveatsFile = tmp.resolve("com/example/perm/Caveats.java");
        assertThat(Files.exists(docFile)).isTrue();
        assertThat(Files.exists(rtFile)).isFalse();      // no longer emitted (T018)
        assertThat(Files.exists(schemaFile)).isTrue();   // new aggregator (T018)
        assertThat(Files.exists(ipFile)).isTrue();
        assertThat(Files.exists(caveatsFile)).isTrue();

        String doc = Files.readString(docFile);
        assertThat(doc).contains("Generated by AuthxCodegen at ");
        assertThat(doc).contains("FOLDER(\"folder\", \"folder\")");

        String schemaCode = Files.readString(schemaFile);
        assertThat(schemaCode).contains(
                "public static final DocumentDescriptor Document = new DocumentDescriptor();");
        assertThat(schemaCode).contains("import com.authx.sdk.PermissionProxy;");
    }

    @Test
    void generateRemovesObsoleteResourceTypesFileIfPresent(
            @org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // Pre-create a stale ResourceTypes.java from a previous codegen run
        Path pkgDir = tmp.resolve("com/example/perm");
        Files.createDirectories(pkgDir);
        Path staleFile = pkgDir.resolve("ResourceTypes.java");
        Files.writeString(staleFile,
                "package com.example.perm; public final class ResourceTypes {}");
        assertThat(Files.exists(staleFile)).isTrue();

        com.authx.sdk.cache.SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of(
                "document", new SchemaCache.DefinitionCache(
                        Set.of("viewer"),
                        Set.of("view"),
                        Map.of("viewer", List.of(SubjectType.of("user"))))));
        com.authx.sdk.SchemaClient schema = new SchemaClient(cache);

        AuthxCodegen.generate(schema, tmp.toString(), "com.example.perm");

        assertThat(Files.exists(staleFile)).isFalse();
        assertThat(Files.exists(pkgDir.resolve("Schema.java"))).isTrue();
    }
}
