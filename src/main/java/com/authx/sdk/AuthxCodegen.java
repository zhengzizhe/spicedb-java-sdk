package com.authx.sdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Code generator: reads schema from a live {@link AuthxClient} and emits
 * one self-contained Java class per resource type.
 *
 * <pre>
 * var client = AuthxClient.builder()
 *     .connection(c -&gt; c.target("localhost:50051").presharedKey("dev-token"))
 *     .build();
 *
 * AuthxCodegen.generate(client, "src/main/java", "com.mycompany.permissions");
 * client.close();
 * </pre>
 *
 * <h2>What it generates</h2>
 *
 * <p>Per resource type in the schema, one file:
 * <ul>
 *   <li>{@code Document.java} — the {@code Rel} and {@code Perm} enums plus
 *       a static API for every operation (grant / revoke / check / who /
 *       findBy / permissionsOf). Typical size: ~150 lines per type.</li>
 * </ul>
 * Plus one shared file:
 * <ul>
 *   <li>{@code ResourceTypes.java} — string constants for every resource
 *       type, useful when you need a dynamic (string) type.</li>
 * </ul>
 *
 * <h2>Shape of the output</h2>
 *
 * <p>Each generated class is <b>pure type metadata</b>: two enums
 * ({@code Rel} and {@code Perm}) plus a {@link ResourceType} constant.
 * No methods. No client-taking statics. No facade to instantiate. Every
 * operation starts from {@link AuthxClient#on(ResourceType)}:
 *
 * <pre>
 * &#64;Service
 * public class DocumentSharingService {
 *     private final AuthxClient client;   // only dependency
 *
 *     public DocumentSharingService(AuthxClient client) { this.client = client; }
 *
 *     public boolean canOpen(String userId, String docId) {
 *         return client.on(Document.TYPE)
 *                      .select(docId)
 *                      .check(Document.Perm.VIEW)
 *                      .by(userId);
 *     }
 *
 *     public EnumMap&lt;Document.Perm, Boolean&gt; toolbarFor(String userId, String docId) {
 *         return client.on(Document.TYPE)
 *                      .select(docId)
 *                      .checkAll()
 *                      .by(userId);
 *     }
 *
 *     public void shareAsEditor(String docId, String userId) {
 *         client.on(Document.TYPE)
 *               .select(docId)
 *               .grant(Document.Rel.EDITOR)
 *               .toUser(userId);
 *     }
 *
 *     public void linkToFolder(String docId, String folderId) {
 *         client.on(Document.TYPE)
 *               .select(docId)
 *               .grant(Document.Rel.FOLDER)
 *               .to(SubjectRef.of("folder", folderId, null));
 *     }
 * }
 * </pre>
 *
 * <p>The generated class is roughly 35 lines per type (two enums + one
 * constant). The typed chain machinery lives entirely in the SDK.
 */
public final class AuthxCodegen {

    private static final System.Logger LOG = System.getLogger(AuthxCodegen.class.getName());

    private AuthxCodegen() {}

    /**
     * Generate typed classes from the live schema of a connected
     * {@link AuthxClient}.
     *
     * @param client      a connected client whose schema has been loaded
     * @param outputDir   source root directory, e.g. {@code "src/main/java"}
     * @param packageName destination package, e.g. {@code "com.mycompany.permissions"}
     */
    public static void generate(AuthxClient client, String outputDir, String packageName) throws IOException {
        var schema = client.schema();
        if (!schema.isLoaded()) {
            throw new IllegalStateException(
                    "Schema not loaded — ensure AuthxClient successfully connected to SpiceDB.");
        }

        Path basePkgDir = Path.of(outputDir, packageName.replace('.', '/'));
        Files.createDirectories(basePkgDir);

        Set<String> types = schema.resourceTypes();
        LOG.log(System.Logger.Level.INFO, "AuthxCodegen: generating for " + types.size() + " resource types");

        for (String type : types) {
            Set<String> relations = schema.relationsOf(type);
            Set<String> permissions = schema.permissionsOf(type);
            // Skip only if both are empty — types with just relations
            // (e.g., group) still get a class with an empty Perm enum.
            if (relations.isEmpty() && permissions.isEmpty()) continue;

            String file = emitTypeClass(type, relations, permissions, packageName);
            Path path = basePkgDir.resolve(toPascalCase(type) + ".java");
            Files.writeString(path, file);
            LOG.log(System.Logger.Level.INFO, "  Generated: " + path);
        }

        // ResourceTypes — string constants for dynamic cases.
        Path resourceTypesPath = basePkgDir.resolve("ResourceTypes.java");
        Files.writeString(resourceTypesPath, emitResourceTypes(packageName, types));
        LOG.log(System.Logger.Level.INFO, "  Generated: " + resourceTypesPath);

        LOG.log(System.Logger.Level.INFO, "AuthxCodegen: done.");
    }

    // ════════════════════════════════════════════════════════════════
    //  Emitter — the whole type lives in one file
    // ════════════════════════════════════════════════════════════════

    static String emitTypeClass(String typeName, Set<String> relations,
                                 Set<String> permissions, String packageName) {
        String className = toPascalCase(typeName);

        var sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.authx.sdk.ResourceType;\n");
        sb.append("import com.authx.sdk.model.Permission;\n");
        sb.append("import com.authx.sdk.model.Relation;\n\n");
        sb.append("/**\n")
          .append(" * Typed metadata for SpiceDB resource type <b>").append(typeName).append("</b>.\n")
          .append(" * Generated by AuthxCodegen at ").append(Instant.now()).append(" — do not edit.\n")
          .append(" *\n")
          .append(" * <p>This class is pure type metadata: two enums plus a\n")
          .append(" * {@link ResourceType} constant. Every operation starts from\n")
          .append(" * {@code client.on(").append(className).append(".TYPE)}:\n")
          .append(" *\n")
          .append(" * <pre>\n")
          .append(" * client.on(").append(className).append(".TYPE).select(id).check(").append(className).append(".Perm.VIEW).by(userId);\n")
          .append(" * client.on(").append(className).append(".TYPE).select(id).grant(").append(className).append(".Rel.EDITOR).toUser(userId);\n")
          .append(" * client.on(").append(className).append(".TYPE).select(id).checkAll().by(userId);\n")
          .append(" * client.on(").append(className).append(".TYPE).findByUser(userId).limit(100).can(").append(className).append(".Perm.VIEW);\n")
          .append(" * </pre>\n")
          .append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");

        // ─── Rel enum (always emitted; empty if no relations) ───
        sb.append("    /** Relations — used with grant / revoke on the typed chain. */\n");
        sb.append("    public enum Rel implements Relation.Named {\n");
        if (relations.isEmpty()) {
            sb.append("        ;\n");
        } else {
            var rels = relations.stream().sorted().toList();
            for (int i = 0; i < rels.size(); i++) {
                String rel = rels.get(i);
                sb.append("        ").append(toConstant(rel)).append("(\"").append(rel).append("\")");
                sb.append(i < rels.size() - 1 ? ",\n" : ";\n");
            }
        }
        sb.append("\n        private final String value;\n");
        sb.append("        Rel(String v) { this.value = v; }\n");
        sb.append("        @Override public String relationName() { return value; }\n");
        sb.append("    }\n\n");

        // ─── Perm enum (always emitted; empty if no permissions) ───
        sb.append("    /** Permissions — used with check / who / findBy on the typed chain. */\n");
        sb.append("    public enum Perm implements Permission.Named {\n");
        if (permissions.isEmpty()) {
            sb.append("        ;\n");
        } else {
            var perms = permissions.stream().sorted().toList();
            for (int i = 0; i < perms.size(); i++) {
                String perm = perms.get(i);
                sb.append("        ").append(toConstant(perm)).append("(\"").append(perm).append("\")");
                sb.append(i < perms.size() - 1 ? ",\n" : ";\n");
            }
        }
        sb.append("\n        private final String value;\n");
        sb.append("        Perm(String v) { this.value = v; }\n");
        sb.append("        @Override public String permissionName() { return value; }\n");
        sb.append("    }\n\n");

        // ─── ResourceType constant ───
        sb.append("    /**\n");
        sb.append("     * Typed type descriptor. Hand this to {@code client.on(...)} to get\n");
        sb.append("     * a chain that accepts only this type's {@link Rel} / {@link Perm} enums.\n");
        sb.append("     */\n");
        sb.append("    public static final ResourceType<Rel, Perm> TYPE =\n");
        sb.append("            ResourceType.of(\"").append(typeName).append("\", Rel.class, Perm.class);\n\n");

        sb.append("    private ").append(className).append("() {}\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Emit a flat {@code ResourceTypes.java} of string constants. */
    static String emitResourceTypes(String packageName, Set<String> allTypes) {
        var sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("/**\n")
          .append(" * Canonical resource type names from the SpiceDB schema.\n")
          .append(" * Generated by AuthxCodegen — do not edit.\n")
          .append(" */\n");
        sb.append("public final class ResourceTypes {\n\n");
        for (String type : allTypes.stream().sorted().toList()) {
            sb.append("    public static final String ").append(toConstant(type))
              .append(" = \"").append(type).append("\";\n");
        }
        sb.append("\n    private ResourceTypes() {}\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  String helpers
    // ════════════════════════════════════════════════════════════════

    static String toPascalCase(String snake) {
        return Arrays.stream(snake.split("[_-]"))
                .map(s -> s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1))
                .collect(Collectors.joining());
    }

    static String toCamelCase(String snake) {
        String pascal = toPascalCase(snake);
        return pascal.isEmpty() ? pascal : Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    static String toConstant(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }
}
