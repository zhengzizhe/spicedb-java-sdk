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
 * a small set of type-safe Java files into the caller's source tree.
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
 * Per resource type in the schema:
 * <ul>
 *   <li>{@code constants/Document.java} — the canonical {@code Rel} and
 *       {@code Perm} enums that name every relation and permission on the
 *       type. ~40 lines.</li>
 *   <li>{@code DocumentResource.java} — a thin facade that extends
 *       {@link TypedResourceFactory}, wiring the two enums into the generic
 *       typed chain. ~15 lines.</li>
 * </ul>
 * Plus one shared file:
 * <ul>
 *   <li>{@code ResourceTypes.java} — string constants for every resource
 *       type, useful when you need dynamic (string-based) access.</li>
 * </ul>
 *
 * <h2>Why the output is thin</h2>
 *
 * <p>Earlier versions of the codegen emitted ~1000-line resource classes
 * containing per-relation grant/revoke action classes, per-permission
 * {@code can<Perm>} / {@code whoCan<Perm>} methods, and typed
 * {@code findBy<SubjectType>} finders — all baked into the generated
 * code. That was wasteful: every business ended up writing its own
 * service layer on top anyway, in its own domain language, and the
 * generated methods just added a second layer to ignore.
 *
 * <p>The thin facade pushes everything schema-agnostic into the SDK core
 * ({@link TypedResourceFactory}, {@link TypedGrantAction},
 * {@link TypedRevokeAction}, {@link com.authx.sdk.model.CheckMatrix}, etc.)
 * where it is shared across all resource types. Runtime subject-type
 * validation via {@link com.authx.sdk.cache.SchemaCache#validateSubject}
 * makes per-relation subject-type safety unnecessary at the type-system
 * level. The generated code is what's actually type-specific: the
 * relation and permission names (enums) and the resource type binding
 * (facade). Nothing else.
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

        String constantsPkg = packageName + ".constants";
        Path basePkgDir = Path.of(outputDir, packageName.replace('.', '/'));
        Path constantsDir = Path.of(outputDir, constantsPkg.replace('.', '/'));
        Files.createDirectories(basePkgDir);
        Files.createDirectories(constantsDir);

        Set<String> types = schema.resourceTypes();
        LOG.log(System.Logger.Level.INFO, "AuthxCodegen: generating for " + types.size() + " resource types");

        for (String type : types) {
            Set<String> relations = schema.relationsOf(type);
            Set<String> permissions = schema.permissionsOf(type);
            if (relations.isEmpty() && permissions.isEmpty()) continue;

            // 1. Rel/Perm enum file — used by every other typed call site.
            String enumFile = emitConstantsClass(type, relations, permissions, constantsPkg);
            Path enumPath = constantsDir.resolve(toPascalCase(type) + ".java");
            Files.writeString(enumPath, enumFile);
            LOG.log(System.Logger.Level.INFO, "  Generated: " + enumPath);

            // 2. Thin resource facade — only emitted when the type has BOTH
            //    at least one relation AND at least one permission. The
            //    facade's generic parameters (Rel, Perm) require both to be
            //    present, and a type with only relations (e.g. a pure
            //    grouping type like {@code group}) can't produce a Perm
            //    enum. Those types are still usable via the stringly API
            //    client.on(type) plus the Rel enum from the constants class.
            if (!relations.isEmpty() && !permissions.isEmpty()) {
                String facadeFile = emitResourceFacade(type, packageName, constantsPkg);
                Path facadePath = basePkgDir.resolve(toPascalCase(type) + "Resource.java");
                Files.writeString(facadePath, facadeFile);
                LOG.log(System.Logger.Level.INFO, "  Generated: " + facadePath);
            }
        }

        // 3. ResourceTypes — string constants for dynamic cases.
        Path resourceTypesPath = basePkgDir.resolve("ResourceTypes.java");
        Files.writeString(resourceTypesPath, emitResourceTypes(packageName, types));
        LOG.log(System.Logger.Level.INFO, "  Generated: " + resourceTypesPath);

        LOG.log(System.Logger.Level.INFO, "AuthxCodegen: done.");
    }

    // ════════════════════════════════════════════════════════════════
    //  Emitters
    // ════════════════════════════════════════════════════════════════

    /** Emit a {@code constants/Document.java}-style enum file. */
    static String emitConstantsClass(String typeName, Set<String> relations,
                                      Set<String> permissions, String packageName) {
        String className = toPascalCase(typeName);
        var sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.authx.sdk.model.Permission;\n");
        sb.append("import com.authx.sdk.model.Relation;\n\n");
        sb.append("/**\n")
          .append(" * Canonical relation and permission names for <b>").append(typeName).append("</b>.\n")
          .append(" * Generated by AuthxCodegen at ").append(Instant.now()).append(" — do not edit.\n")
          .append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");

        if (!relations.isEmpty()) {
            sb.append("    /** Relations — used with TypedHandle.grant / revoke. */\n");
            sb.append("    public enum Rel implements Relation.Named {\n");
            var rels = relations.stream().sorted().toList();
            for (int i = 0; i < rels.size(); i++) {
                String rel = rels.get(i);
                sb.append("        ").append(toConstant(rel))
                  .append("(\"").append(rel).append("\")");
                sb.append(i < rels.size() - 1 ? ",\n" : ";\n");
            }
            sb.append("\n        private final String value;\n");
            sb.append("        Rel(String v) { this.value = v; }\n");
            sb.append("        @Override public String relationName() { return value; }\n");
            sb.append("    }\n\n");
        }

        if (!permissions.isEmpty()) {
            sb.append("    /** Permissions — used with TypedHandle.check / who / findBy. */\n");
            sb.append("    public enum Perm implements Permission.Named {\n");
            var perms = permissions.stream().sorted().toList();
            for (int i = 0; i < perms.size(); i++) {
                String perm = perms.get(i);
                sb.append("        ").append(toConstant(perm))
                  .append("(\"").append(perm).append("\")");
                sb.append(i < perms.size() - 1 ? ",\n" : ";\n");
            }
            sb.append("\n        private final String value;\n");
            sb.append("        Perm(String v) { this.value = v; }\n");
            sb.append("        @Override public String permissionName() { return value; }\n");
            sb.append("    }\n\n");
        }

        sb.append("    private ").append(className).append("() {}\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Emit the thin resource facade — just a {@code TypedResourceFactory}
     * subclass parameterised on the generated {@code Rel} and {@code Perm}
     * enums. All the interesting logic lives in {@link TypedResourceFactory}
     * itself; this class exists to give the user an ergonomic entry point
     * that carries the right generic types automatically.
     */
    static String emitResourceFacade(String typeName, String packageName, String constantsPkg) {
        String className = toPascalCase(typeName) + "Resource";
        String enumBase = constantsPkg + "." + toPascalCase(typeName);
        var sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.authx.sdk.AuthxClient;\n");
        sb.append("import com.authx.sdk.TypedResourceFactory;\n");
        sb.append("import ").append(enumBase).append(";\n\n");
        sb.append("/**\n")
          .append(" * Typed resource facade for <b>").append(typeName).append("</b>.\n")
          .append(" * Generated by AuthxCodegen — do not edit.\n")
          .append(" *\n")
          .append(" * <pre>\n")
          .append(" * ").append(className).append(" doc = new ").append(className).append("(client);\n")
          .append(" * doc.select(\"id\").grant(").append(toPascalCase(typeName)).append(".Rel.EDITOR).toUser(\"bob\");\n")
          .append(" * boolean ok = doc.select(\"id\").check(").append(toPascalCase(typeName)).append(".Perm.VIEW).by(\"alice\");\n")
          .append(" * </pre>\n")
          .append(" */\n");
        sb.append("public final class ").append(className)
          .append(" extends TypedResourceFactory<").append(toPascalCase(typeName)).append(".Rel, ")
          .append(toPascalCase(typeName)).append(".Perm> {\n\n");
        sb.append("    public ").append(className).append("(AuthxClient client) {\n");
        sb.append("        init(\"").append(typeName).append("\", client);\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Emit a flat {@code ResourceTypes.java} of string constants. */
    static String emitResourceTypes(String packageName, Set<String> allTypes) {
        var sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("/**\n")
          .append(" * Canonical resource type names from the SpiceDB schema.\n")
          .append(" * Use these constants when you need a dynamic (string) resource type.\n")
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

    static String toConstant(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }
}
