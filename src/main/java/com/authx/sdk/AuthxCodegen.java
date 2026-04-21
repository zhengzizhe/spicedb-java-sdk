package com.authx.sdk;

import com.authx.sdk.model.SubjectType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Code generator: reads schema from a live {@link AuthxClient} (or a
 * pre-built {@link SchemaClient}) and emits one Java class per
 * resource type plus per-caveat classes.
 *
 * <pre>
 * var client = AuthxClient.builder()
 *     .connection(c -&gt; c.target("localhost:50051").presharedKey("dev-token"))
 *     .build();
 * AuthxCodegen.generate(client, "test-app/src/main/java", "com.authx.testapp.schema");
 * client.close();
 * </pre>
 *
 * <p>Per resource type, emits:
 * <ul>
 *   <li>{@code Xxx.java} — {@code Rel} enum (with {@code subjectTypes()}
 *       metadata per value), {@code Perm} enum, and a
 *       {@code public static final ResourceType<Rel, Perm> TYPE} constant</li>
 * </ul>
 * Plus:
 * <ul>
 *   <li>{@code ResourceTypes.java} — string constants for every type</li>
 *   <li>{@code XxxCaveat.java} — one per caveat (NAME, parameter-name
 *       constants, {@code ref(...)} + {@code context(...)} factories)</li>
 *   <li>{@code Caveats.java} — string constants for every caveat name</li>
 * </ul>
 *
 * <p>The emitted {@code Rel} enum carries full {@link SubjectType}
 * metadata via a varargs constructor ({@code Rel(String v, String... sts)})
 * and a {@code subjectTypes()} override on {@link com.authx.sdk.model.Relation.Named}.
 * Business code can use this at runtime for subject-type inference and
 * validation without needing an extra schema lookup.
 */
public final class AuthxCodegen {

    private static final System.Logger LOG = System.getLogger(AuthxCodegen.class.getName());

    private AuthxCodegen() {}

    /** Live-client entry point — pulls schema from {@code client.schema()}. */
    public static void generate(AuthxClient client, String outputDir, String packageName) throws IOException {
        generate(client.schema(), outputDir, packageName);
    }

    /** Schema-client entry point — useful in tests without a real {@code AuthxClient}. */
    public static void generate(SchemaClient schema, String outputDir, String packageName) throws IOException {
        if (!schema.isLoaded()) {
            throw new IllegalStateException(
                    "Schema not loaded — ensure AuthxClient successfully connected to SpiceDB.");
        }

        Path basePkgDir = Path.of(outputDir, packageName.replace('.', '/'));
        Files.createDirectories(basePkgDir);

        Set<String> types = schema.resourceTypes();
        LOG.log(System.Logger.Level.INFO,
                com.authx.sdk.trace.LogCtx.fmt("AuthxCodegen: generating for {0} resource types", types.size()));

        for (String type : types) {
            Set<String> relations = schema.relationsOf(type);
            Set<String> permissions = schema.permissionsOf(type);
            // Emit a class for every declared type, even subject-only ones
            // such as {@code user} (no relations / no permissions). These
            // still need a {@code TYPE} descriptor so business code can
            // pass {@code User.TYPE} to the typed subject overloads on
            // Grant/Revoke/Check/Lookup.

            var relSTs = schema.allSubjectTypes(type);
            String file = emitTypeClass(type, relations, permissions, relSTs, packageName);
            Path out = basePkgDir.resolve(toPascalCase(type) + ".java");
            Files.writeString(out, file);
            LOG.log(System.Logger.Level.INFO,
                    com.authx.sdk.trace.LogCtx.fmt("  Generated: {0}", out));
        }

        Path resourceTypesPath = basePkgDir.resolve("ResourceTypes.java");
        Files.writeString(resourceTypesPath, emitResourceTypes(packageName, types));
        LOG.log(System.Logger.Level.INFO,
                com.authx.sdk.trace.LogCtx.fmt("  Generated: {0}", resourceTypesPath));

        Set<String> caveatNames = schema.getCaveatNames();
        if (!caveatNames.isEmpty()) {
            for (String name : caveatNames) {
                var def = schema.getCaveat(name);
                if (def == null) continue;
                String src = emitCaveatClass(def.name(), def.parameters(),
                        def.expression(), def.comment(), packageName);
                Path out = basePkgDir.resolve(toPascalCase(name) + ".java");
                Files.writeString(out, src);
                LOG.log(System.Logger.Level.INFO,
                        com.authx.sdk.trace.LogCtx.fmt("  Generated caveat: {0}", out));
            }
            Path caveatsPath = basePkgDir.resolve("Caveats.java");
            Files.writeString(caveatsPath, emitCaveats(packageName, caveatNames));
            LOG.log(System.Logger.Level.INFO,
                    com.authx.sdk.trace.LogCtx.fmt("  Generated: {0}", caveatsPath));
        }
        LOG.log(System.Logger.Level.INFO, "AuthxCodegen: done.");
    }

    // ════════════════════════════════════════════════════════════════
    //  Emitters — package-private for unit testing
    // ════════════════════════════════════════════════════════════════

    static String emitTypeClass(String typeName,
                                Set<String> relations,
                                Set<String> permissions,
                                Map<String, List<SubjectType>> relationSubjectTypes,
                                String packageName) {
        String className = toPascalCase(typeName);
        var sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.authx.sdk.ResourceType;\n");
        sb.append("import com.authx.sdk.model.Permission;\n");
        sb.append("import com.authx.sdk.model.Relation;\n");
        sb.append("import com.authx.sdk.model.SubjectType;\n\n");
        sb.append("import java.util.Arrays;\n");
        sb.append("import java.util.List;\n\n");

        sb.append("/**\n")
          .append(" * Typed metadata for SpiceDB resource type <b>").append(typeName).append("</b>.\n")
          .append(" * Generated by AuthxCodegen at ").append(Instant.now()).append(" — do not edit.\n")
          .append(" *\n")
          .append(" * <pre>\n")
          .append(" * client.on(").append(className).append(".TYPE).select(id).check(").append(className).append(".Perm.VIEW).by(userId);\n")
          .append(" * client.on(").append(className).append(".TYPE).select(id).grant(").append(className).append(".Rel.EDITOR).to(userId);\n")
          .append(" * </pre>\n")
          .append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");

        // ─── Rel enum with subject types varargs ───
        sb.append("    /** Relations — used with grant / revoke on the typed chain. */\n");
        sb.append("    public enum Rel implements Relation.Named {\n");
        if (relations.isEmpty()) {
            sb.append("        ;\n");
        } else {
            var rels = relations.stream().sorted().toList();
            for (int i = 0; i < rels.size(); i++) {
                String rel = rels.get(i);
                sb.append("        ").append(toConstant(rel)).append("(\"").append(rel).append("\"");
                List<SubjectType> sts = relationSubjectTypes.getOrDefault(rel, List.of());
                for (SubjectType st : sts) {
                    sb.append(", \"").append(st.toRef()).append("\"");
                }
                sb.append(")");
                sb.append(i < rels.size() - 1 ? ",\n" : ";\n");
            }
        }
        sb.append("\n        private final String value;\n");
        sb.append("        private final List<SubjectType> subjectTypes;\n");
        sb.append("        Rel(String v, String... sts) {\n");
        sb.append("            this.value = v;\n");
        sb.append("            this.subjectTypes = Arrays.stream(sts).map(SubjectType::parse).toList();\n");
        sb.append("        }\n");
        sb.append("        @Override public String relationName() { return value; }\n");
        sb.append("        @Override public List<SubjectType> subjectTypes() { return subjectTypes; }\n");
        sb.append("    }\n\n");

        // ─── Perm enum ───
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
        sb.append("    /** Typed descriptor — hand this to {@code client.on(...)}. */\n");
        sb.append("    public static final ResourceType<Rel, Perm> TYPE =\n");
        sb.append("            ResourceType.of(\"").append(typeName).append("\", Rel.class, Perm.class);\n\n");

        sb.append("    private ").append(className).append("() {}\n");
        sb.append("}\n");
        return sb.toString();
    }

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

    static String mapSpiceDbType(String spiceType) {
        return switch (spiceType) {
            case "string" -> "String";
            case "int", "uint" -> "Long";
            case "double" -> "Double";
            case "bool" -> "Boolean";
            case "any" -> "Object";
            default -> {
                if (spiceType.startsWith("list<") && spiceType.endsWith(">")) {
                    String inner = spiceType.substring(5, spiceType.length() - 1);
                    yield "List<" + mapSpiceDbType(inner) + ">";
                }
                if (spiceType.startsWith("map<") && spiceType.endsWith(">")) {
                    String inner = spiceType.substring(4, spiceType.length() - 1);
                    int comma = inner.indexOf(',');
                    if (comma > 0) {
                        yield "Map<" + mapSpiceDbType(inner.substring(0, comma).trim())
                                + ", " + mapSpiceDbType(inner.substring(comma + 1).trim()) + ">";
                    }
                }
                yield spiceType;
            }
        };
    }

    static String emitCaveatClass(String caveatName, Map<String, String> parameters,
                                  String expression, String comment, String packageName) {
        String className = toPascalCase(caveatName);
        var sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.authx.sdk.model.CaveatRef;\n\n");
        sb.append("import java.util.LinkedHashMap;\n");
        sb.append("import java.util.Map;\n\n");
        sb.append("/**\n");
        sb.append(" * Typed caveat <b>").append(caveatName).append("</b>.\n");
        if (!comment.isEmpty()) sb.append(" * <p>").append(comment).append("\n");
        if (!expression.isEmpty()) sb.append(" * <p>CEL: {@code ").append(expression).append("}\n");
        sb.append(" * Generated by AuthxCodegen — do not edit.\n");
        sb.append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");
        sb.append("    public static final String NAME = \"").append(caveatName).append("\";\n\n");
        for (var e : parameters.entrySet()) {
            String javaType = mapSpiceDbType(e.getValue());
            sb.append("    /** Parameter {@code ").append(e.getKey())
              .append("} — expected type: {@code ")
              .append(javaType.replace("<", "&lt;").replace(">", "&gt;"))
              .append("} */\n");
            sb.append("    public static final String ").append(toConstant(e.getKey()))
              .append(" = \"").append(e.getKey()).append("\";\n\n");
        }
        sb.append("    /** Build a {@link CaveatRef} for grant-time binding. */\n");
        sb.append("    public static CaveatRef ref(Object... keyValues) {\n");
        sb.append("        return new CaveatRef(NAME, toMap(keyValues));\n");
        sb.append("    }\n\n");
        sb.append("    /** Build a context map for check-time evaluation. */\n");
        sb.append("    public static Map<String, Object> context(Object... keyValues) {\n");
        sb.append("        return toMap(keyValues);\n");
        sb.append("    }\n\n");
        sb.append("    private static Map<String, Object> toMap(Object... kv) {\n");
        sb.append("        if (kv.length % 2 != 0) {\n");
        sb.append("            throw new IllegalArgumentException(\n");
        sb.append("                    \"keyValues must have even length (alternating key, value pairs)\");\n");
        sb.append("        }\n");
        sb.append("        var map = new LinkedHashMap<String, Object>();\n");
        sb.append("        for (int i = 0; i < kv.length; i += 2) {\n");
        sb.append("            if (!(kv[i] instanceof String)) {\n");
        sb.append("                throw new IllegalArgumentException(\n");
        sb.append("                        \"Key at index \" + i + \" must be a String, got: \" + kv[i].getClass().getName());\n");
        sb.append("            }\n");
        sb.append("            map.put((String) kv[i], kv[i + 1]);\n");
        sb.append("        }\n");
        sb.append("        return map;\n");
        sb.append("    }\n\n");
        sb.append("    private ").append(className).append("() {}\n");
        sb.append("}\n");
        return sb.toString();
    }

    static String emitCaveats(String packageName, Set<String> allCaveats) {
        var sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("/**\n")
          .append(" * Canonical caveat names from the SpiceDB schema.\n")
          .append(" * Generated by AuthxCodegen — do not edit.\n")
          .append(" */\n");
        sb.append("public final class Caveats {\n\n");
        for (String n : allCaveats.stream().sorted().toList()) {
            sb.append("    public static final String ").append(toConstant(n))
              .append(" = \"").append(n).append("\";\n");
        }
        sb.append("\n    private Caveats() {}\n");
        sb.append("}\n");
        return sb.toString();
    }

    static String toPascalCase(String snake) {
        return Arrays.stream(snake.split("[_-]"))
                .map(s -> s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1))
                .collect(Collectors.joining());
    }

    static String toConstant(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }
}
