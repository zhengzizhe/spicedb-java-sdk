package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.trace.LogCtx;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * AuthxClient client = AuthxClient.builder()
 *     .connection(c -&gt; c.target("localhost:50051").presharedKey("dev-token"))
 *     .build();
 * AuthxCodegen.generate(client, "test-app/src/main/java", "com.authx.testapp.schema");
 * client.close();
 * </pre>
 *
 * <p>Per resource type, emits {@code Xxx.java} with a same-name resource
 * object plus {@code Rel} and {@code Perm} enums. Business code can static
 * import that object and call {@code client.on(Project)} while still using
 * {@code Project.Rel.MEMBER}.
 * Caveats are emitted as {@code XxxCaveat.java} classes plus a
 * {@code Caveats.java} name aggregator.
 *
 * <p>The emitted {@code Rel} enum carries full {@link SubjectType}
 * metadata via a varargs constructor ({@code Rel(String v, String... sts)})
 * and a {@code subjectTypes()} override on {@link com.authx.sdk.model.Relation.Named}.
 * Business code can use this at runtime for subject-type inference and
 * validation without needing an extra schema lookup. Generated classes avoid
 * descriptor/proxy boilerplate so business-visible code stays small.
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
                LogCtx.fmt("AuthxCodegen: generating for {0} resource types", types.size()));

        for (String type : types) {
            Set<String> relations = schema.relationsOf(type);
            Set<String> permissions = schema.permissionsOf(type);
            // Emit a class for every declared type, even subject-only ones
            // such as user. Each class carries its same-name typed descriptor.

            Map<String, List<SubjectType>> relSTs = schema.allSubjectTypes(type);
            String file = emitTypeClass(type, relations, permissions, relSTs, packageName);
            Path out = basePkgDir.resolve(toPascalCase(type) + ".java");
            Files.writeString(out, file);
            LOG.log(System.Logger.Level.INFO,
                    LogCtx.fmt("  Generated: {0}", out));
        }

        // ResourceTypes.java is no longer emitted — type names are carried by
        // the generated descriptor classes. Delete any lingering file from a
        // previous generator run so the tree stays clean.
        Path oldResourceTypesPath = basePkgDir.resolve("ResourceTypes.java");
        if (Files.exists(oldResourceTypesPath)) {
            Files.delete(oldResourceTypesPath);
            LOG.log(System.Logger.Level.INFO,
                    LogCtx.fmt("  Removed obsolete: {0}", oldResourceTypesPath));
        }

        Path oldSchemaPath = basePkgDir.resolve("Schema.java");
        if (Files.exists(oldSchemaPath)) {
            Files.delete(oldSchemaPath);
            LOG.log(System.Logger.Level.INFO,
                    LogCtx.fmt("  Removed obsolete: {0}", oldSchemaPath));
        }

        Set<String> caveatNames = schema.getCaveatNames();
        if (!caveatNames.isEmpty()) {
            for (String name : caveatNames) {
                SchemaCache.CaveatDef def = schema.getCaveat(name);
                if (def == null) continue;
                String src = emitCaveatClass(def.name(), def.parameters(),
                        def.expression(), def.comment(), packageName);
                Path out = basePkgDir.resolve(toPascalCase(name) + ".java");
                Files.writeString(out, src);
                LOG.log(System.Logger.Level.INFO,
                        LogCtx.fmt("  Generated caveat: {0}", out));
            }
            Path caveatsPath = basePkgDir.resolve("Caveats.java");
            Files.writeString(caveatsPath, emitCaveats(packageName, caveatNames));
            LOG.log(System.Logger.Level.INFO,
                    LogCtx.fmt("  Generated: {0}", caveatsPath));
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
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.authx.sdk.ResourceType;\n");
        sb.append("import com.authx.sdk.model.Permission;\n");
        sb.append("import com.authx.sdk.model.Relation;\n");
        sb.append("import com.authx.sdk.model.SubjectType;\n\n");
        sb.append("import java.util.Arrays;\n");
        sb.append("import java.util.List;\n\n");

        sb.append("// Generated by AuthxCodegen. Do not edit.\n");
        sb.append("public final class ").append(className).append(" {\n\n");
        sb.append("    public static final Resource ").append(className)
          .append(" = new Resource();\n\n");
        sb.append("    public static final class Resource extends ResourceType<Rel, Perm> {\n");
        sb.append("        public final RelProxy Rel = new RelProxy();\n");
        sb.append("        public final PermProxy Perm = new PermProxy();\n\n");
        sb.append("        private Resource() {\n");
        sb.append("            super(\"").append(typeName).append("\", ")
          .append(packageName).append(".").append(className).append(".Rel::values, ")
          .append(packageName).append(".").append(className).append(".Perm::values);\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // ─── Rel enum with subject types varargs ───
        sb.append("    public enum Rel implements Relation.Named {\n");
        if (relations.isEmpty()) {
            sb.append("        ;\n");
        } else {
            List<String> rels = relations.stream().sorted().toList();
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

        sb.append("    public static final class RelProxy {\n");
        for (String rel : relations.stream().sorted().toList()) {
            String constant = toConstant(rel);
            sb.append("        public final Rel ").append(constant)
              .append(" = Rel.").append(constant).append(";\n");
        }
        sb.append("        private RelProxy() {}\n");
        sb.append("    }\n\n");

        // ─── Perm enum ───
        sb.append("    public enum Perm implements Permission.Named {\n");
        if (permissions.isEmpty()) {
            sb.append("        ;\n");
        } else {
            List<String> perms = permissions.stream().sorted().toList();
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

        sb.append("    public static final class PermProxy {\n");
        for (String perm : permissions.stream().sorted().toList()) {
            String constant = toConstant(perm);
            sb.append("        public final Perm ").append(constant)
              .append(" = Perm.").append(constant).append(";\n");
        }
        sb.append("        private PermProxy() {}\n");
        sb.append("    }\n\n");

        sb.append("    private ").append(className).append("() {}\n");
        sb.append("}\n");
        return sb.toString();
    }

    static String mapSpiceDbType(String spiceType) {
        return switch (spiceType) {
            case "string" -> "String";
            case "int", "uint" -> "Long";
            case "double" -> "Double";
            case "bool" -> "Boolean";
            case "timestamp" -> "Instant";
            case "duration" -> "Duration";
            case "ipaddress", "ip_address" -> "InetAddress";
            case "bytes" -> "byte[]";
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
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.authx.sdk.model.CaveatContext;\n");
        sb.append("import com.authx.sdk.model.CaveatRef;\n\n");
        sb.append("import java.net.InetAddress;\n");
        sb.append("import java.time.Duration;\n");
        sb.append("import java.time.Instant;\n");
        sb.append("import java.util.LinkedHashMap;\n");
        sb.append("import java.util.LinkedHashSet;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Map;\n");
        sb.append("import java.util.Objects;\n");
        sb.append("import java.util.Set;\n\n");
        sb.append("/**\n");
        sb.append(" * Typed caveat <b>").append(caveatName).append("</b>.\n");
        if (!comment.isEmpty()) sb.append(" * <p>").append(comment).append("\n");
        if (!expression.isEmpty()) sb.append(" * <p>CEL: {@code ").append(expression).append("}\n");
        sb.append(" * Generated by AuthxCodegen — do not edit.\n");
        sb.append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");
        sb.append("    public static final String NAME = \"").append(caveatName).append("\";\n\n");
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            String javaType = mapSpiceDbType(e.getValue());
            sb.append("    /** Parameter {@code ").append(e.getKey())
              .append("} — expected type: {@code ")
              .append(javaType.replace("<", "&lt;").replace(">", "&gt;"))
              .append("} */\n");
            sb.append("    public static final String ").append(toConstant(e.getKey()))
              .append(" = \"").append(e.getKey()).append("\";\n\n");
        }
        sb.append("    public static Builder builder() {\n");
        sb.append("        return new Builder();\n");
        sb.append("    }\n\n");
        sb.append("    /** Build a {@link CaveatRef} for grant-time binding. */\n");
        sb.append("    public static CaveatRef ref(Object... keyValues) {\n");
        sb.append("        Builder builder = builder();\n");
        sb.append("        builder.putAll(keyValues);\n");
        sb.append("        return builder.ref();\n");
        sb.append("    }\n\n");
        sb.append("    /** Build a complete {@link CaveatRef}; all schema parameters must be present. */\n");
        sb.append("    public static CaveatRef completeRef(Object... keyValues) {\n");
        sb.append("        Builder builder = builder();\n");
        sb.append("        builder.putAll(keyValues);\n");
        sb.append("        return builder.completeRef();\n");
        sb.append("    }\n\n");
        sb.append("    /** Build a typed context for check-time evaluation. */\n");
        sb.append("    public static CaveatContext context(Object... keyValues) {\n");
        sb.append("        Builder builder = builder();\n");
        sb.append("        builder.putAll(keyValues);\n");
        sb.append("        return builder.context();\n");
        sb.append("    }\n\n");
        sb.append("    /** Build a complete typed context; all schema parameters must be present. */\n");
        sb.append("    public static CaveatContext completeContext(Object... keyValues) {\n");
        sb.append("        Builder builder = builder();\n");
        sb.append("        builder.putAll(keyValues);\n");
        sb.append("        return builder.completeContext();\n");
        sb.append("    }\n\n");
        sb.append("    public static final class Builder {\n\n");
        sb.append("        private final LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();\n");
        sb.append("        private final Set<String> present = new LinkedHashSet<String>();\n\n");
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            String javaType = mapSpiceDbType(e.getValue());
            String paramName = e.getKey();
            sb.append("        public Builder ").append(toCamelCase(paramName))
              .append("(").append(javaType).append(" value) {\n");
            sb.append("            return put(").append(toConstant(paramName)).append(", value);\n");
            sb.append("        }\n\n");
        }
        sb.append("        public CaveatRef ref() {\n");
        sb.append("            return new CaveatRef(NAME, context());\n");
        sb.append("        }\n\n");
        sb.append("        public CaveatRef completeRef() {\n");
        sb.append("            return new CaveatRef(NAME, completeContext());\n");
        sb.append("        }\n\n");
        sb.append("        public CaveatContext context() {\n");
        sb.append("            return new CaveatContext(values);\n");
        sb.append("        }\n\n");
        sb.append("        public CaveatContext completeContext() {\n");
        sb.append("            requireAllPresent();\n");
        sb.append("            return context();\n");
        sb.append("        }\n\n");
        sb.append("        private Builder put(String key, Object value) {\n");
        sb.append("            values.put(key, validate(key, value));\n");
        sb.append("            present.add(key);\n");
        sb.append("            return this;\n");
        sb.append("        }\n\n");
        sb.append("        private void putAll(Object... kv) {\n");
        sb.append("            Objects.requireNonNull(kv, \"keyValues\");\n");
        sb.append("            if (kv.length % 2 != 0) {\n");
        sb.append("                throw new IllegalArgumentException(\n");
        sb.append("                        \"keyValues must have even length (alternating key, value pairs)\");\n");
        sb.append("            }\n");
        sb.append("            for (int i = 0; i < kv.length; i += 2) {\n");
        sb.append("                if (!(kv[i] instanceof String key)) {\n");
        sb.append("                    throw new IllegalArgumentException(\n");
        sb.append("                            \"Key at index \" + i + \" must be a String, got: \"\n");
        sb.append("                                    + (kv[i] == null ? \"null\" : kv[i].getClass().getName()));\n");
        sb.append("                }\n");
        sb.append("                put(key, kv[i + 1]);\n");
        sb.append("            }\n");
        sb.append("        }\n\n");
        sb.append("        private void requireAllPresent() {\n");
        for (String paramName : parameters.keySet()) {
            sb.append("            if (!present.contains(").append(toConstant(paramName)).append(")) {\n");
            sb.append("                throw new IllegalArgumentException(\"Missing caveat parameter: ")
              .append(paramName).append("\");\n");
            sb.append("            }\n");
        }
        sb.append("        }\n");
        sb.append("    }\n\n");
        sb.append("    private static Object validate(String key, Object value) {\n");
        sb.append("        return switch (key) {\n");
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            sb.append("            case ").append(toConstant(e.getKey()))
              .append(" -> ").append(validatorName(e.getKey()))
              .append("(value);\n");
        }
        sb.append("            default -> throw new IllegalArgumentException(\"Unknown caveat parameter: \" + key);\n");
        sb.append("        };\n");
        sb.append("    }\n\n");
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            appendValidator(sb, e.getKey(), e.getValue());
        }
        sb.append("    private ").append(className).append("() {}\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendValidator(StringBuilder sb, String paramName, String spiceType) {
        String javaType = mapSpiceDbType(spiceType);
        String label = javaType.replace("\"", "\\\"");
        sb.append("    private static Object ").append(validatorName(paramName)).append("(Object value) {\n");
        if ("Object".equals(javaType)) {
            sb.append("        return value;\n");
        } else {
            sb.append("        Objects.requireNonNull(value, \"").append(paramName).append("\");\n");
            if ("byte[]".equals(javaType)) {
                sb.append("        if (value instanceof byte[] bytes) {\n");
                sb.append("            return bytes.clone();\n");
                sb.append("        }\n");
            } else if (javaType.startsWith("List<")) {
                sb.append("        if (value instanceof List<?>) {\n");
                sb.append("            return value;\n");
                sb.append("        }\n");
            } else if (javaType.startsWith("Map<")) {
                sb.append("        if (value instanceof Map<?, ?>) {\n");
                sb.append("            return value;\n");
                sb.append("        }\n");
            } else {
                sb.append("        if (value instanceof ").append(javaType).append(") {\n");
                sb.append("            return value;\n");
                sb.append("        }\n");
            }
            sb.append("        throw new IllegalArgumentException(\"Parameter ").append(paramName)
              .append(" must be ").append(label).append(", got: \" + value.getClass().getName());\n");
        }
        sb.append("    }\n\n");
    }

    private static String validatorName(String name) {
        return "validate" + toPascalCase(name);
    }

    static String emitCaveats(String packageName, Set<String> allCaveats) {
        StringBuilder sb = new StringBuilder();
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

    static String toCamelCase(String name) {
        String pascal = toPascalCase(name);
        return pascal.isEmpty() ? pascal : Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    static String toConstant(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }
}
