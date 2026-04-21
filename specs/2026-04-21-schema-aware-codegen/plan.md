# Schema-Aware Codegen Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore `AuthxCodegen` to AuthX SDK and upgrade it with schema-aware subject-type metadata, enabling progressively shorter, fail-fast, typed business code via four incremental PRs.

**Architecture:** Put the schema read path back inside the SDK (`SchemaLoader` → `SchemaCache` → `SchemaClient`) but keep it **metadata-only** — no permission decision cache (ADR 2026-04-18). Extend `Relation.Named` with a default `subjectTypes()` method so codegen enums can carry allowed-subject metadata per relation. Use that metadata to drive (PR-B) runtime validation, (PR-C) single-type subject inference + typed `ResourceType` overloads, and (PR-D) typed caveat factories.

**Tech Stack:** Java 21, Gradle, gRPC (`com.authzed.api.v1.ExperimentalServiceGrpc` — already on classpath via `authzed:1.5.4`), `java.lang.System.Logger`. No new third-party dependencies.

---

## File Structure

### New SDK files (PR-A)

| Path | Responsibility |
|---|---|
| `src/main/java/com/authx/sdk/model/SubjectType.java` | Record `(type, relation, wildcard)` with `parse()` / `of()` / `wildcard()` / `toRef()` factories. |
| `src/main/java/com/authx/sdk/cache/SchemaCache.java` | Metadata-only cache: `DefinitionCache(relations, permissions, relationSubjectTypes)` + `CaveatDef(name, parameters, expression, comment)`. `AtomicReference<Map<String, DefinitionCache>>` for relations, separate for caveats. **No decision cache.** |
| `src/main/java/com/authx/sdk/transport/SchemaLoader.java` | Calls `ExperimentalServiceGrpc.experimentalReflectSchema()`, maps `ExpRelationSubjectType` → `SubjectType`, writes both maps into `SchemaCache`. Non-fatal on `UNIMPLEMENTED`. |
| `src/main/java/com/authx/sdk/SchemaClient.java` | Public wrapper that exposes the cache on `AuthxClient.schema()`. |
| `src/main/java/com/authx/sdk/AuthxCodegen.java` | Static `generate(client, outputDir, packageName)` entry. Emits `XxxType.java` / `XxxCaveat.java` / `ResourceTypes.java` / `Caveats.java`. |

### Modified SDK files (PR-A)

| Path | Change |
|---|---|
| `src/main/java/com/authx/sdk/model/Relation.java` | Add `default List<SubjectType> subjectTypes() { return List.of(); }` to `Named` interface. |
| `src/main/java/com/authx/sdk/AuthxClient.java` | Add `schema()` accessor; wire `SchemaClient` from builder. |
| `src/main/java/com/authx/sdk/AuthxClientBuilder.java` | Add `loadSchemaOnStart(boolean)` flag; call `SchemaLoader.load()` in `build()`. |

### Modified SDK files (PR-B)

| Path | Change |
|---|---|
| `src/main/java/com/authx/sdk/cache/SchemaCache.java` | Add `validateSubject(resourceType, relation, subjectRef)` (port from historical code). |
| `src/main/java/com/authx/sdk/ResourceFactory.java` | Hold `SchemaCache schemaCache` (nullable); pass to handles. |
| `src/main/java/com/authx/sdk/ResourceHandle.java` | Hold `SchemaCache schemaCache`; pass to actions. |
| `src/main/java/com/authx/sdk/action/GrantAction.java` | Add nullable `SchemaCache` ctor param; call `validateSubject` per `(relation, subjectRef)` pair before `writeRelationships`. |
| `src/main/java/com/authx/sdk/action/RevokeAction.java` | Same as GrantAction. |
| `src/main/java/com/authx/sdk/action/BatchGrantAction.java` | Same. |
| `src/main/java/com/authx/sdk/action/BatchRevokeAction.java` | Same. |

### Modified SDK files (PR-C)

| Path | Change |
|---|---|
| `src/main/java/com/authx/sdk/action/GrantAction.java` | Override `to(String)` to do single-type inference when arg has no `:`; add `to(ResourceType, String)`, `to(ResourceType, String, String)`, `toWildcard(ResourceType)`, `to(ResourceType, Iterable<String>)`. |
| `src/main/java/com/authx/sdk/action/RevokeAction.java` | Mirror overloads on `from`. |
| `src/main/java/com/authx/sdk/action/CheckAction.java` | Add `by(ResourceType, String)` + `byAll(ResourceType, Iterable<String>)`. |
| `src/main/java/com/authx/sdk/TypedCheckAction.java` | Mirror typed overloads for `by`. |
| `src/main/java/com/authx/sdk/action/WhoBuilder.java` | Add `(ResourceType, Permission.Named)` start; keep `(String, Permission.Named)` for back-compat. |
| `src/main/java/com/authx/sdk/LookupQuery.java` | Add `findBy(ResourceType, String)` + `findBy(ResourceType, Iterable<String>)`. |
| `src/main/java/com/authx/sdk/TypedFinder.java` | Mirror typed overloads. |
| `test-app/src/main/java/com/authx/testapp/service/DocumentSharingService.java` | Migrate all call sites per spec appendix B. |

### Modified SDK files (PR-D)

| Path | Change |
|---|---|
| `deploy/schema.zed` | Add `caveat ip_allowlist(cidrs list<string>, client_ip string) { ... }` (demonstration caveat). |
| `test-app/src/main/java/com/authx/testapp/service/ConditionalShareService.java` (new) | Demonstrate `IpAllowlist.ref(...)` + `IpAllowlist.context(...)` flow. |

### Regenerated per-phase artifacts

- **PR-A (req-7):** `test-app/src/main/java/com/authx/testapp/schema/{Department,Document,Folder,Group,Organization,Space,ResourceTypes,User}.java` regenerated via codegen against `deploy/schema.zed` — **adds** `User.java` (currently missing), all files gain `subjectTypes` varargs in `Rel`.
- **PR-D (req-15):** Same schema directory + new `IpAllowlist.java` + `Caveats.java`.

---

## Branch strategy

| Phase | Branch | Base |
|---|---|---|
| PR-A | `feature/pr-a-codegen-restore` | `main` @ `4a27a84` (spec commit) |
| PR-B | `feature/pr-b-subject-validation` | merged PR-A |
| PR-C | `feature/pr-c-typed-subject-overloads` | merged PR-B |
| PR-D | `feature/pr-d-typed-caveat` | merged PR-C |

Each feature branch becomes one PR. **Never start PR-B until PR-A is merged** — PR-B needs `SchemaCache` to exist.

---

# Phase 0: Setup

### Task T001: Create PR-A feature branch, verify baseline green

**Files:** none (git state only).

**Steps:**
1. `git checkout main && git pull`
2. `git checkout -b feature/pr-a-codegen-restore`
3. `./gradlew test -x :test-app:test -x :cluster-test:test` — record baseline "all green"
4. No commit (setup only).

---

# Phase 1: PR-A — Restore codegen + SDK schema read path

## Task T002: `SubjectType` record + test [SR:req-4]

**Files:**
- Create: `src/main/java/com/authx/sdk/model/SubjectType.java`
- Create: `src/test/java/com/authx/sdk/model/SubjectTypeTest.java`

**Steps:**

1. **Write failing test** `src/test/java/com/authx/sdk/model/SubjectTypeTest.java`:

   ```java
   package com.authx.sdk.model;

   import org.junit.jupiter.api.Test;
   import static org.assertj.core.api.Assertions.*;

   class SubjectTypeTest {

       @Test void parse_typeOnly() {
           var st = SubjectType.parse("user");
           assertThat(st.type()).isEqualTo("user");
           assertThat(st.relation()).isNull();
           assertThat(st.wildcard()).isFalse();
           assertThat(st.toRef()).isEqualTo("user");
       }

       @Test void parse_subjectRelation() {
           var st = SubjectType.parse("group#member");
           assertThat(st.type()).isEqualTo("group");
           assertThat(st.relation()).isEqualTo("member");
           assertThat(st.wildcard()).isFalse();
           assertThat(st.toRef()).isEqualTo("group#member");
       }

       @Test void parse_wildcard() {
           var st = SubjectType.parse("user:*");
           assertThat(st.type()).isEqualTo("user");
           assertThat(st.relation()).isNull();
           assertThat(st.wildcard()).isTrue();
           assertThat(st.toRef()).isEqualTo("user:*");
       }

       @Test void of_type() {
           assertThat(SubjectType.of("user").toRef()).isEqualTo("user");
       }

       @Test void of_typeAndRelation() {
           assertThat(SubjectType.of("group", "member").toRef()).isEqualTo("group#member");
       }

       @Test void wildcardFactory() {
           var st = SubjectType.wildcard("user");
           assertThat(st.wildcard()).isTrue();
           assertThat(st.toRef()).isEqualTo("user:*");
       }

       @Test void parse_rejectsInvalid() {
           assertThatThrownBy(() -> SubjectType.parse(""))
                   .isInstanceOf(IllegalArgumentException.class);
       }

       @Test void roundTrip() {
           for (String ref : new String[]{"user", "group#member", "user:*", "department#all_members"}) {
               assertThat(SubjectType.parse(ref).toRef()).isEqualTo(ref);
           }
       }
   }
   ```

2. **Run:** `./gradlew test --tests SubjectTypeTest` — should fail (class missing).

3. **Implement** `src/main/java/com/authx/sdk/model/SubjectType.java`:

   ```java
   package com.authx.sdk.model;

   import org.jspecify.annotations.Nullable;

   import java.util.Objects;

   /**
    * Declared subject shape allowed on a relation, as reported by
    * SpiceDB's ReflectSchema. Produced by codegen and attached to
    * {@code Relation.Named.subjectTypes()} so the SDK (and business code)
    * can introspect which subjects a relation accepts.
    *
    * <p>Three canonical shapes:
    * <ul>
    *   <li>{@code user}              — typed subject, no sub-relation</li>
    *   <li>{@code group#member}      — typed subject with sub-relation</li>
    *   <li>{@code user:*}            — public wildcard</li>
    * </ul>
    *
    * @param type     subject definition name (e.g. {@code "user"})
    * @param relation optional sub-relation for subject-sets (nullable)
    * @param wildcard {@code true} iff this is a {@code type:*} declaration
    */
   public record SubjectType(String type, @Nullable String relation, boolean wildcard) {
       public SubjectType {
           Objects.requireNonNull(type, "type");
       }

       /** Parse {@code "user"} / {@code "group#member"} / {@code "user:*"}. */
       public static SubjectType parse(String s) {
           Objects.requireNonNull(s, "s");
           if (s.isEmpty()) {
               throw new IllegalArgumentException("empty SubjectType ref");
           }
           if (s.endsWith(":*")) {
               return new SubjectType(s.substring(0, s.length() - 2), null, true);
           }
           int hash = s.indexOf('#');
           if (hash >= 0) {
               return new SubjectType(s.substring(0, hash), s.substring(hash + 1), false);
           }
           return new SubjectType(s, null, false);
       }

       public static SubjectType of(String type) {
           return new SubjectType(type, null, false);
       }

       public static SubjectType of(String type, String relation) {
           return new SubjectType(type, relation, false);
       }

       public static SubjectType wildcard(String type) {
           return new SubjectType(type, null, true);
       }

       /** Canonical string form. Inverse of {@link #parse(String)}. */
       public String toRef() {
           if (wildcard) return type + ":*";
           if (relation != null && !relation.isEmpty()) return type + "#" + relation;
           return type;
       }
   }
   ```

4. **Run:** `./gradlew test --tests SubjectTypeTest` — should pass.
5. **Run:** `./gradlew compileJava`.
6. **Commit:** `git add src/main/java/com/authx/sdk/model/SubjectType.java src/test/java/com/authx/sdk/model/SubjectTypeTest.java && git commit -m "feat(model): SubjectType record for relation subject metadata (req-4)"`.

---

## Task T003: `Relation.Named.subjectTypes()` default method + test [SR:req-4]

**Files:**
- Modify: `src/main/java/com/authx/sdk/model/Relation.java`
- Create: `src/test/java/com/authx/sdk/model/RelationNamedSubjectTypesTest.java`

**Steps:**

1. **Write failing test** `src/test/java/com/authx/sdk/model/RelationNamedSubjectTypesTest.java`:

   ```java
   package com.authx.sdk.model;

   import org.junit.jupiter.api.Test;
   import java.util.Arrays;
   import java.util.List;
   import static org.assertj.core.api.Assertions.assertThat;

   class RelationNamedSubjectTypesTest {

       /** Legacy enum without subjectTypes metadata — default returns empty. */
       enum LegacyRel implements Relation.Named {
           EDITOR("editor");
           private final String v;
           LegacyRel(String v) { this.v = v; }
           @Override public String relationName() { return v; }
       }

       /** Codegen-style enum with per-value subjectTypes metadata. */
       enum SchemaRel implements Relation.Named {
           FOLDER("folder", "folder"),
           VIEWER("viewer", "user", "group#member", "user:*");
           private final String v;
           private final List<SubjectType> subjectTypes;
           SchemaRel(String v, String... sts) {
               this.v = v;
               this.subjectTypes = Arrays.stream(sts).map(SubjectType::parse).toList();
           }
           @Override public String relationName() { return v; }
           @Override public List<SubjectType> subjectTypes() { return subjectTypes; }
       }

       @Test void defaultIsEmpty() {
           assertThat(LegacyRel.EDITOR.subjectTypes()).isEmpty();
       }

       @Test void overrideReturnsAttached() {
           assertThat(SchemaRel.FOLDER.subjectTypes())
                   .containsExactly(SubjectType.of("folder"));
           assertThat(SchemaRel.VIEWER.subjectTypes())
                   .containsExactly(
                           SubjectType.of("user"),
                           SubjectType.of("group", "member"),
                           SubjectType.wildcard("user"));
       }
   }
   ```

2. **Run:** `./gradlew test --tests RelationNamedSubjectTypesTest` — fails (method missing).

3. **Implement** — edit `src/main/java/com/authx/sdk/model/Relation.java`, add import and default method:

   ```java
   // Add to imports:
   import java.util.List;

   // Add inside the Named interface, after toRelation():
   /**
    * Allowed subject shapes on this relation, as declared in the SpiceDB
    * schema. Codegen enums override this to return the metadata emitted
    * from {@code SubjectType.parse(...)}; hand-written enums without
    * codegen metadata get the empty default.
    *
    * <p>Used by the SDK for:
    * <ul>
    *   <li>runtime subject validation ({@code SchemaCache.validateSubject})</li>
    *   <li>single-type subject inference ({@code .to(id)})</li>
    *   <li>business-code introspection ({@code Document.Rel.VIEWER.subjectTypes()})</li>
    * </ul>
    */
   default List<SubjectType> subjectTypes() { return List.of(); }
   ```

4. **Run:** `./gradlew test --tests RelationNamedSubjectTypesTest` — passes.
5. **Run:** `./gradlew compileJava` — verify existing `Named` impls still compile (default handles them).
6. **Commit:** `git add src/main/java/com/authx/sdk/model/Relation.java src/test/java/com/authx/sdk/model/RelationNamedSubjectTypesTest.java && git commit -m "feat(model): Relation.Named.subjectTypes() default method (req-4)"`.

---

## Task T004: `SchemaCache` scaffolding (metadata-only) + test [SR:req-2]

**Files:**
- Create: `src/main/java/com/authx/sdk/cache/SchemaCache.java`
- Create: `src/test/java/com/authx/sdk/cache/SchemaCacheTest.java`

**Steps:**

1. **Write failing test** `src/test/java/com/authx/sdk/cache/SchemaCacheTest.java`:

   ```java
   package com.authx.sdk.cache;

   import com.authx.sdk.model.SubjectType;
   import org.junit.jupiter.api.Test;

   import java.util.List;
   import java.util.Map;
   import java.util.Set;

   import static org.assertj.core.api.Assertions.assertThat;

   class SchemaCacheTest {

       @Test void emptyByDefault() {
           var c = new SchemaCache();
           assertThat(c.hasSchema()).isFalse();
           assertThat(c.getResourceTypes()).isEmpty();
           assertThat(c.getRelations("document")).isEmpty();
           assertThat(c.getPermissions("document")).isEmpty();
           assertThat(c.getSubjectTypes("document", "viewer")).isEmpty();
           assertThat(c.getCaveatNames()).isEmpty();
           assertThat(c.getCaveat("ip_allowlist")).isNull();
       }

       @Test void updateFromMap_populatesDefinitions() {
           var c = new SchemaCache();
           Map<String, SchemaCache.DefinitionCache> defs = Map.of(
                   "document", new SchemaCache.DefinitionCache(
                           Set.of("folder", "viewer"),
                           Set.of("view", "edit"),
                           Map.of(
                                   "folder", List.of(SubjectType.of("folder")),
                                   "viewer", List.of(
                                           SubjectType.of("user"),
                                           SubjectType.wildcard("user")))));
           c.updateFromMap(defs);

           assertThat(c.hasSchema()).isTrue();
           assertThat(c.getResourceTypes()).containsExactlyInAnyOrder("document");
           assertThat(c.getRelations("document")).containsExactlyInAnyOrder("folder", "viewer");
           assertThat(c.getPermissions("document")).containsExactlyInAnyOrder("view", "edit");
           assertThat(c.getSubjectTypes("document", "folder"))
                   .containsExactly(SubjectType.of("folder"));
           assertThat(c.getSubjectTypes("document", "viewer"))
                   .containsExactly(SubjectType.of("user"), SubjectType.wildcard("user"));
           assertThat(c.hasResourceType("document")).isTrue();
           assertThat(c.hasResourceType("widget")).isFalse();
       }

       @Test void updateCaveats_populates() {
           var c = new SchemaCache();
           c.updateCaveats(Map.of(
                   "ip_allowlist", new SchemaCache.CaveatDef(
                           "ip_allowlist",
                           Map.of("cidrs", "list<string>"),
                           "client_ip in cidrs",
                           "")));
           assertThat(c.getCaveatNames()).containsExactly("ip_allowlist");
           var def = c.getCaveat("ip_allowlist");
           assertThat(def).isNotNull();
           assertThat(def.parameters()).containsEntry("cidrs", "list<string>");
           assertThat(def.expression()).isEqualTo("client_ip in cidrs");
       }
   }
   ```

2. **Run:** `./gradlew test --tests SchemaCacheTest` — fails (class missing).

3. **Implement** `src/main/java/com/authx/sdk/cache/SchemaCache.java`:

   ```java
   package com.authx.sdk.cache;

   import com.authx.sdk.model.SubjectType;

   import org.jspecify.annotations.Nullable;

   import java.util.List;
   import java.util.Map;
   import java.util.Objects;
   import java.util.Set;
   import java.util.concurrent.atomic.AtomicReference;

   /**
    * Metadata-only schema cache. Holds:
    * <ul>
    *   <li>resource definitions — relations + permissions + per-relation subject types</li>
    *   <li>caveat definitions — name + params + expression + comment</li>
    * </ul>
    *
    * <p><b>Deliberately excludes</b> any permission / check-decision caching
    * (ADR 2026-04-18 — SpiceDB server-side dispatch cache handles that).
    *
    * <p>Thread-safe via {@link AtomicReference} swaps. Readers never block.
    */
   public class SchemaCache {

       /** Per-type bundle of relations, permissions, and relation→subject-types. */
       public record DefinitionCache(
               Set<String> relations,
               Set<String> permissions,
               Map<String, List<SubjectType>> relationSubjectTypes) {
           public DefinitionCache {
               relations = relations != null ? Set.copyOf(relations) : Set.of();
               permissions = permissions != null ? Set.copyOf(permissions) : Set.of();
               relationSubjectTypes = relationSubjectTypes != null
                       ? Map.copyOf(relationSubjectTypes)
                       : Map.of();
           }
       }

       /** Caveat definition reflected from the schema. */
       public record CaveatDef(
               String name,
               Map<String, String> parameters,
               String expression,
               String comment) {
           public CaveatDef {
               Objects.requireNonNull(name, "name");
               parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
               expression = expression != null ? expression : "";
               comment = comment != null ? comment : "";
           }
       }

       private final AtomicReference<Map<String, DefinitionCache>> defs =
               new AtomicReference<>(Map.of());
       private final AtomicReference<Map<String, CaveatDef>> caveats =
               new AtomicReference<>(Map.of());

       /** Replace all definitions atomically. */
       public void updateFromMap(Map<String, DefinitionCache> definitions) {
           defs.set(definitions != null ? Map.copyOf(definitions) : Map.of());
       }

       /** Replace all caveats atomically. */
       public void updateCaveats(Map<String, CaveatDef> in) {
           caveats.set(in != null ? Map.copyOf(in) : Map.of());
       }

       public boolean hasSchema() { return !defs.get().isEmpty(); }

       public Set<String> getResourceTypes() { return defs.get().keySet(); }

       public boolean hasResourceType(String type) { return defs.get().containsKey(type); }

       public Set<String> getRelations(String type) {
           var d = defs.get().get(type);
           return d != null ? d.relations() : Set.of();
       }

       public Set<String> getPermissions(String type) {
           var d = defs.get().get(type);
           return d != null ? d.permissions() : Set.of();
       }

       public List<SubjectType> getSubjectTypes(String type, String relation) {
           var d = defs.get().get(type);
           if (d == null) return List.of();
           var sts = d.relationSubjectTypes().get(relation);
           return sts != null ? sts : List.of();
       }

       public Map<String, List<SubjectType>> getAllSubjectTypes(String type) {
           var d = defs.get().get(type);
           return d != null ? d.relationSubjectTypes() : Map.of();
       }

       public Set<String> getCaveatNames() { return caveats.get().keySet(); }

       public @Nullable CaveatDef getCaveat(String name) { return caveats.get().get(name); }
   }
   ```

4. **Run:** `./gradlew test --tests SchemaCacheTest` — passes.
5. **Run:** `./gradlew compileJava`.
6. **Commit:** `git add src/main/java/com/authx/sdk/cache/SchemaCache.java src/test/java/com/authx/sdk/cache/SchemaCacheTest.java && git commit -m "feat(cache): metadata-only SchemaCache (req-2)"`.

---

## Task T005: `SchemaLoader` — live gRPC ReflectSchema call [SR:req-1]

**Files:**
- Create: `src/main/java/com/authx/sdk/transport/SchemaLoader.java`
- Create: `src/test/java/com/authx/sdk/transport/SchemaLoaderTest.java`

**Steps:**

1. **Write failing test** with a fake `ExperimentalServiceImplBase` behind in-process gRPC:

   ```java
   package com.authx.sdk.transport;

   import com.authx.sdk.cache.SchemaCache;
   import com.authzed.api.v1.ExperimentalReflectSchemaRequest;
   import com.authzed.api.v1.ExperimentalReflectSchemaResponse;
   import com.authzed.api.v1.ExperimentalServiceGrpc;
   import com.authzed.api.v1.ReflectionSchema.ExpCaveat;
   import com.authzed.api.v1.ReflectionSchema.ExpCaveatParameter;
   import com.authzed.api.v1.ReflectionSchema.ExpDefinition;
   import com.authzed.api.v1.ReflectionSchema.ExpPermission;
   import com.authzed.api.v1.ReflectionSchema.ExpRelation;
   import com.authzed.api.v1.ReflectionSchema.ExpRelationSubjectType;
   import io.grpc.ManagedChannel;
   import io.grpc.Metadata;
   import io.grpc.Server;
   import io.grpc.Status;
   import io.grpc.inprocess.InProcessChannelBuilder;
   import io.grpc.inprocess.InProcessServerBuilder;
   import io.grpc.stub.StreamObserver;
   import org.junit.jupiter.api.AfterEach;
   import org.junit.jupiter.api.Test;

   import static org.assertj.core.api.Assertions.assertThat;

   class SchemaLoaderTest {

       private Server server;
       private ManagedChannel channel;

       @AfterEach void cleanup() throws Exception {
           if (channel != null) channel.shutdownNow().awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
           if (server != null) server.shutdownNow().awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
       }

       private void startServer(ExperimentalServiceGrpc.ExperimentalServiceImplBase impl) throws Exception {
           String name = "schema-loader-" + System.nanoTime();
           server = InProcessServerBuilder.forName(name).directExecutor().addService(impl).build().start();
           channel = InProcessChannelBuilder.forName(name).directExecutor().build();
       }

       @Test void loadsDefinitionsAndCaveats() throws Exception {
           startServer(new ExperimentalServiceGrpc.ExperimentalServiceImplBase() {
               @Override
               public void experimentalReflectSchema(ExperimentalReflectSchemaRequest req,
                                                     StreamObserver<ExperimentalReflectSchemaResponse> obs) {
                   obs.onNext(ExperimentalReflectSchemaResponse.newBuilder()
                           .addDefinitions(ExpDefinition.newBuilder()
                                   .setName("document")
                                   .addRelations(ExpRelation.newBuilder()
                                           .setName("folder")
                                           .addSubjectTypes(ExpRelationSubjectType.newBuilder()
                                                   .setSubjectDefinitionName("folder")))
                                   .addRelations(ExpRelation.newBuilder()
                                           .setName("viewer")
                                           .addSubjectTypes(ExpRelationSubjectType.newBuilder()
                                                   .setSubjectDefinitionName("user"))
                                           .addSubjectTypes(ExpRelationSubjectType.newBuilder()
                                                   .setSubjectDefinitionName("user")
                                                   .setIsPublicWildcard(true)))
                                   .addPermissions(ExpPermission.newBuilder().setName("view")))
                           .addCaveats(ExpCaveat.newBuilder()
                                   .setName("ip_allowlist")
                                   .setExpression("client_ip in cidrs")
                                   .addParameters(ExpCaveatParameter.newBuilder()
                                           .setName("cidrs")
                                           .setType("list<string>")))
                           .build());
                   obs.onCompleted();
               }
           });

           var cache = new SchemaCache();
           boolean ok = new SchemaLoader().load(channel, new Metadata(), cache);
           assertThat(ok).isTrue();
           assertThat(cache.hasSchema()).isTrue();
           assertThat(cache.getResourceTypes()).containsExactly("document");
           assertThat(cache.getRelations("document")).containsExactlyInAnyOrder("folder", "viewer");
           assertThat(cache.getPermissions("document")).containsExactly("view");
           assertThat(cache.getSubjectTypes("document", "viewer")).hasSize(2);
           assertThat(cache.getCaveatNames()).containsExactly("ip_allowlist");
           assertThat(cache.getCaveat("ip_allowlist").parameters())
                   .containsEntry("cidrs", "list<string>");
       }

       @Test void unimplementedIsNonFatal() throws Exception {
           startServer(new ExperimentalServiceGrpc.ExperimentalServiceImplBase() {
               @Override
               public void experimentalReflectSchema(ExperimentalReflectSchemaRequest req,
                                                     StreamObserver<ExperimentalReflectSchemaResponse> obs) {
                   obs.onError(Status.UNIMPLEMENTED.asRuntimeException());
               }
           });
           var cache = new SchemaCache();
           var loader = new SchemaLoader();
           assertThat(loader.load(channel, new Metadata(), cache)).isFalse();
           // Second attempt short-circuits (reflectSupported = false).
           assertThat(loader.load(channel, new Metadata(), cache)).isFalse();
           assertThat(cache.hasSchema()).isFalse();
       }
   }
   ```

2. **Run:** `./gradlew test --tests SchemaLoaderTest` — fails (class missing).

3. **Implement** `src/main/java/com/authx/sdk/transport/SchemaLoader.java`:

   ```java
   package com.authx.sdk.transport;

   import com.authx.sdk.cache.SchemaCache;
   import com.authx.sdk.model.SubjectType;
   import com.authzed.api.v1.Consistency;
   import com.authzed.api.v1.ExperimentalReflectSchemaRequest;
   import com.authzed.api.v1.ExperimentalServiceGrpc;
   import io.grpc.ManagedChannel;
   import io.grpc.Metadata;
   import io.grpc.Status;
   import io.grpc.StatusRuntimeException;
   import io.grpc.stub.MetadataUtils;

   import java.util.ArrayList;
   import java.util.HashMap;
   import java.util.HashSet;
   import java.util.LinkedHashMap;
   import java.util.List;
   import java.util.Map;
   import java.util.Set;
   import java.util.concurrent.TimeUnit;

   /**
    * Loads schema metadata from SpiceDB's {@code ExperimentalReflectSchema}
    * gRPC and writes it into {@link SchemaCache}. Non-fatal on failure:
    * returns {@code false}, leaves the cache untouched, and remembers
    * UNIMPLEMENTED so we skip the roundtrip next time.
    */
   public class SchemaLoader {

       private static final System.Logger LOG =
               System.getLogger(SchemaLoader.class.getName());

       /** Flipped false once we observe UNIMPLEMENTED. Per-instance. */
       private volatile boolean reflectSupported = true;

       /**
        * @return {@code true} iff the response was consumed and the cache
        *         was updated. {@code false} for UNIMPLEMENTED, transport
        *         errors, or any parse problem — the SDK keeps running
        *         without schema validation.
        */
       public boolean load(ManagedChannel channel, Metadata authMetadata, SchemaCache cache) {
           if (!reflectSupported) return false;
           try {
               var stub = ExperimentalServiceGrpc.newBlockingStub(channel)
                       .withDeadlineAfter(3, TimeUnit.SECONDS)
                       .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authMetadata));

               var resp = stub.experimentalReflectSchema(
                       ExperimentalReflectSchemaRequest.newBuilder()
                               .setConsistency(Consistency.newBuilder().setFullyConsistent(true))
                               .build());

               Map<String, SchemaCache.DefinitionCache> defs = new HashMap<>();
               for (var def : resp.getDefinitionsList()) {
                   Set<String> relations = new HashSet<>();
                   Map<String, List<SubjectType>> relSTs = new HashMap<>();
                   for (var rel : def.getRelationsList()) {
                       relations.add(rel.getName());
                       List<SubjectType> sts = new ArrayList<>();
                       for (var st : rel.getSubjectTypesList()) {
                           String relName = st.getOptionalRelationName();
                           if (relName != null && relName.isEmpty()) relName = null;
                           sts.add(new SubjectType(
                                   st.getSubjectDefinitionName(),
                                   relName,
                                   st.getIsPublicWildcard()));
                       }
                       relSTs.put(rel.getName(), sts);
                   }
                   Set<String> permissions = new HashSet<>();
                   for (var perm : def.getPermissionsList()) {
                       permissions.add(perm.getName());
                   }
                   defs.put(def.getName(), new SchemaCache.DefinitionCache(
                           relations, permissions, relSTs));
               }
               cache.updateFromMap(defs);

               Map<String, SchemaCache.CaveatDef> caveats = new HashMap<>();
               for (var cav : resp.getCaveatsList()) {
                   Map<String, String> params = new LinkedHashMap<>();
                   for (var p : cav.getParametersList()) {
                       params.put(p.getName(), p.getType());
                   }
                   caveats.put(cav.getName(), new SchemaCache.CaveatDef(
                           cav.getName(), params, cav.getExpression(), cav.getComment()));
               }
               cache.updateCaveats(caveats);

               LOG.log(System.Logger.Level.INFO,
                       "Schema loaded: {0} definitions, {1} caveats",
                       defs.size(), caveats.size());
               return true;
           } catch (StatusRuntimeException e) {
               if (e.getStatus().getCode() == Status.Code.UNIMPLEMENTED) {
                   reflectSupported = false;
                   LOG.log(System.Logger.Level.INFO,
                           "ExperimentalReflectSchema unsupported by server — schema validation disabled");
                   return false;
               }
               LOG.log(System.Logger.Level.WARNING,
                       "Schema load failed (non-fatal): {0}", e.getMessage());
               return false;
           } catch (Exception e) {
               LOG.log(System.Logger.Level.WARNING,
                       "Schema load failed (non-fatal): {0}", e.getMessage());
               return false;
           }
       }
   }
   ```

4. **Run:** `./gradlew test --tests SchemaLoaderTest` — passes.
5. **Run:** `./gradlew compileJava`.
6. **Commit:** `git add src/main/java/com/authx/sdk/transport/SchemaLoader.java src/test/java/com/authx/sdk/transport/SchemaLoaderTest.java && git commit -m "feat(transport): SchemaLoader gRPC ReflectSchema caller (req-1)"`.

---

## Task T006: `SchemaClient` public wrapper + `AuthxClient.schema()` [SR:req-3]

**Files:**
- Create: `src/main/java/com/authx/sdk/SchemaClient.java`
- Create: `src/test/java/com/authx/sdk/SchemaClientTest.java`
- Modify: `src/main/java/com/authx/sdk/AuthxClient.java`

**Steps:**

1. **Write failing test** `src/test/java/com/authx/sdk/SchemaClientTest.java`:

   ```java
   package com.authx.sdk;

   import com.authx.sdk.cache.SchemaCache;
   import com.authx.sdk.model.SubjectType;
   import org.junit.jupiter.api.Test;

   import java.util.List;
   import java.util.Map;
   import java.util.Set;

   import static org.assertj.core.api.Assertions.assertThat;

   class SchemaClientTest {

       @Test void delegatesToCache() {
           var cache = new SchemaCache();
           cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                   Set.of("folder", "viewer"),
                   Set.of("view"),
                   Map.of("folder", List.of(SubjectType.of("folder"))))));
           var sc = new SchemaClient(cache);
           assertThat(sc.isLoaded()).isTrue();
           assertThat(sc.resourceTypes()).containsExactly("document");
           assertThat(sc.relationsOf("document")).containsExactlyInAnyOrder("folder", "viewer");
           assertThat(sc.permissionsOf("document")).containsExactly("view");
           assertThat(sc.hasResourceType("document")).isTrue();
           assertThat(sc.subjectTypesOf("document", "folder"))
                   .containsExactly(SubjectType.of("folder"));
       }

       @Test void nullCacheBehavesEmpty() {
           var sc = new SchemaClient(null);
           assertThat(sc.isLoaded()).isFalse();
           assertThat(sc.resourceTypes()).isEmpty();
           assertThat(sc.getCaveatNames()).isEmpty();
       }
   }
   ```

2. **Run:** `./gradlew test --tests SchemaClientTest` — fails.

3. **Implement** `src/main/java/com/authx/sdk/SchemaClient.java`:

   ```java
   package com.authx.sdk;

   import com.authx.sdk.cache.SchemaCache;
   import com.authx.sdk.model.SubjectType;

   import org.jspecify.annotations.Nullable;

   import java.util.List;
   import java.util.Map;
   import java.util.Set;

   /**
    * Public read-only view of the loaded SpiceDB schema.
    * Exposed via {@link AuthxClient#schema()}.
    *
    * <pre>
    * var schema = client.schema();
    * schema.resourceTypes();           // ["document", "folder", "group"]
    * schema.relationsOf("document");   // ["owner", "editor", "viewer", "folder", ...]
    * schema.permissionsOf("document"); // ["view", "edit", "delete", ...]
    * schema.subjectTypesOf("document", "viewer");  // [user, group#member, user:*]
    * </pre>
    */
   public class SchemaClient {

       private final @Nullable SchemaCache cache;

       public SchemaClient(@Nullable SchemaCache cache) {
           this.cache = cache;
       }

       /** {@code true} iff at least one definition is loaded. */
       public boolean isLoaded() {
           return cache != null && cache.hasSchema();
       }

       public Set<String> resourceTypes() {
           return cache != null ? cache.getResourceTypes() : Set.of();
       }

       public boolean hasResourceType(String type) {
           return cache != null && cache.hasResourceType(type);
       }

       public Set<String> relationsOf(String resourceType) {
           return cache != null ? cache.getRelations(resourceType) : Set.of();
       }

       public Set<String> permissionsOf(String resourceType) {
           return cache != null ? cache.getPermissions(resourceType) : Set.of();
       }

       public List<SubjectType> subjectTypesOf(String resourceType, String relation) {
           return cache != null ? cache.getSubjectTypes(resourceType, relation) : List.of();
       }

       public Map<String, List<SubjectType>> allSubjectTypes(String resourceType) {
           return cache != null ? cache.getAllSubjectTypes(resourceType) : Map.of();
       }

       public Set<String> getCaveatNames() {
           return cache != null ? cache.getCaveatNames() : Set.of();
       }

       public @Nullable SchemaCache.CaveatDef getCaveat(String name) {
           return cache != null ? cache.getCaveat(name) : null;
       }
   }
   ```

4. **Modify** `src/main/java/com/authx/sdk/AuthxClient.java` — add a field and accessor:

   ```java
   // Import at top:
   import com.authx.sdk.cache.SchemaCache;

   // Add field (nullable, set by builder; in-memory client passes null):
   private final SchemaClient schemaClient;

   // Update primary constructor:
   AuthxClient(SdkTransport transport,
               SdkInfrastructure infra,
               SdkObservability observability,
               SdkConfig config,
               HealthProbe healthProbe,
               SchemaClient schemaClient) {
       this.transport = Objects.requireNonNull(transport);
       this.infra = Objects.requireNonNull(infra);
       this.observability = Objects.requireNonNull(observability);
       this.config = Objects.requireNonNull(config);
       this.healthProbe = Objects.requireNonNull(healthProbe, "healthProbe");
       this.schemaClient = schemaClient != null ? schemaClient : new SchemaClient(null);
   }

   // Update inMemory() to pass null for schemaClient:
   public static AuthxClient inMemory() {
       var bus = new DefaultTypedEventBus();
       var lm = new LifecycleManager(bus);
       lm.begin(); lm.complete();
       var infra = new SdkInfrastructure(null, null, Runnable::run, lm);
       var observability = new SdkObservability(new SdkMetrics(), bus, null);
       var config = new SdkConfig(PolicyRegistry.withDefaults(), false, false);
       return new AuthxClient(new InMemoryTransport(), infra, observability, config, HealthProbe.up(), null);
   }

   // Public accessor:
   /** Schema view. Always non-null; may report {@code isLoaded() == false}. */
   public SchemaClient schema() { return schemaClient; }
   ```

   (Builder will be updated in T007 to actually call the constructor with a populated `SchemaClient`.)

5. **Run:** `./gradlew test --tests SchemaClientTest` — passes.
6. **Run:** `./gradlew compileJava`.
7. **Commit:** `git add -u src/main/java/com/authx/sdk/AuthxClient.java && git add src/main/java/com/authx/sdk/SchemaClient.java src/test/java/com/authx/sdk/SchemaClientTest.java && git commit -m "feat(sdk): SchemaClient + AuthxClient.schema() (req-3)"`.

---

## Task T007: Builder wires `SchemaLoader` → `SchemaCache` → `SchemaClient` [SR:req-5]

**Files:**
- Modify: `src/main/java/com/authx/sdk/AuthxClientBuilder.java`
- Create: `src/test/java/com/authx/sdk/AuthxClientBuilderSchemaTest.java`

**Steps:**

1. **Write failing test** `src/test/java/com/authx/sdk/AuthxClientBuilderSchemaTest.java` (verifies field + defaults via reflection — full integration is covered by live tests):

   ```java
   package com.authx.sdk;

   import org.junit.jupiter.api.Test;

   import java.lang.reflect.Field;

   import static org.assertj.core.api.Assertions.assertThat;

   class AuthxClientBuilderSchemaTest {

       @Test void loadSchemaOnStartDefaultsTrue() throws Exception {
           var b = AuthxClient.builder();
           Field f = AuthxClientBuilder.class.getDeclaredField("loadSchemaOnStart");
           f.setAccessible(true);
           assertThat(f.getBoolean(b)).isTrue();
       }

       @Test void loadSchemaOnStartCanBeDisabled() throws Exception {
           var b = AuthxClient.builder().loadSchemaOnStart(false);
           Field f = AuthxClientBuilder.class.getDeclaredField("loadSchemaOnStart");
           f.setAccessible(true);
           assertThat(f.getBoolean(b)).isFalse();
       }

       @Test void inMemoryClientReportsSchemaNotLoaded() {
           try (var client = AuthxClient.inMemory()) {
               assertThat(client.schema().isLoaded()).isFalse();
               assertThat(client.schema().resourceTypes()).isEmpty();
           }
       }
   }
   ```

2. **Modify** `src/main/java/com/authx/sdk/AuthxClientBuilder.java`:

   - Add field (near the other feature flags):
     ```java
     private boolean loadSchemaOnStart = true;
     ```

   - Add setter:
     ```java
     /**
      * Whether to block on {@code ExperimentalReflectSchema} at startup
      * (default {@code true}). Set {@code false} for offline/in-memory
      * builds or if you're running against a SpiceDB version without
      * ReflectSchema. When disabled (or when loading fails),
      * {@code client.schema().isLoaded()} will report {@code false}
      * and subject validation fails open.
      */
     public AuthxClientBuilder loadSchemaOnStart(boolean v) {
         this.loadSchemaOnStart = v;
         return this;
     }
     ```

   - In `build()` — after the `ManagedChannel` is constructed and before/as part of producing the `AuthxClient` — add:
     ```java
     var schemaCache = new com.authx.sdk.cache.SchemaCache();
     if (loadSchemaOnStart && channel != null) {
         new com.authx.sdk.transport.SchemaLoader().load(channel, authMetadata, schemaCache);
     }
     var schemaClient = new com.authx.sdk.SchemaClient(schemaCache);
     ```
     and pass `schemaClient` into the `new AuthxClient(...)` constructor call.

   (Exact `build()` edit points depend on existing code — look for where `authMetadata` and `channel` are in scope, insert the block just before the client is constructed.)

3. **Run:** `./gradlew test --tests AuthxClientBuilderSchemaTest` — passes.
4. **Run:** `./gradlew compileJava`.
5. **Commit:** `git add -u src/main/java/com/authx/sdk/AuthxClientBuilder.java && git add src/test/java/com/authx/sdk/AuthxClientBuilderSchemaTest.java && git commit -m "feat(sdk): AuthxClientBuilder.loadSchemaOnStart() wiring (req-5)"`.

---

## Task T008: `AuthxCodegen` — emit schema-aware enums + ResourceTypes + caveats [SR:req-6]

**Files:**
- Create: `src/main/java/com/authx/sdk/AuthxCodegen.java`
- Create: `src/test/java/com/authx/sdk/AuthxCodegenTest.java`

**Steps:**

1. **Write failing test** using an `AuthxClient.inMemory()`-style fake schema. Since codegen takes `AuthxClient`, we feed it a client whose `SchemaClient` is pre-populated. Simplest path: build a `SchemaCache` by hand, wrap in `SchemaClient`, construct an `AuthxClient` via reflection-free factory. To avoid fighting the builder, split testability: **also expose internal emit* methods package-private** and test them directly on schema data.

   ```java
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

       @Test void emitsRelEnumWithSubjectTypesVarargs() {
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
           // Rel enum with varargs constructor
           assertThat(code).contains("FOLDER(\"folder\", \"folder\")");
           assertThat(code).contains("VIEWER(\"viewer\", \"user\", \"group#member\", \"user:*\")");
           assertThat(code).contains("Rel(String v, String... sts)");
           assertThat(code).contains("Arrays.stream(sts).map(SubjectType::parse).toList()");
           assertThat(code).contains("public List<SubjectType> subjectTypes()");
           // Perm enum unchanged shape
           assertThat(code).contains("VIEW(\"view\")");
           // TYPE constant
           assertThat(code).contains("ResourceType.of(\"document\", Rel.class, Perm.class)");
       }

       @Test void emitsResourceTypesConstants() {
           String code = AuthxCodegen.emitResourceTypes(
                   "com.example.perm",
                   Set.of("document", "folder", "user"));
           assertThat(code).contains("public static final String DOCUMENT = \"document\";");
           assertThat(code).contains("public static final String FOLDER = \"folder\";");
           assertThat(code).contains("public static final String USER = \"user\";");
       }

       @Test void emitsCaveatClass() {
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

       @Test void endToEndFromFakeSchema(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
           var cache = new SchemaCache();
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
           var schema = new SchemaClient(cache);

           AuthxCodegen.generate(schema, tmp.toString(), "com.example.perm");

           Path docFile = tmp.resolve("com/example/perm/Document.java");
           Path rtFile  = tmp.resolve("com/example/perm/ResourceTypes.java");
           Path ipFile  = tmp.resolve("com/example/perm/IpAllowlist.java");
           Path caveatsFile = tmp.resolve("com/example/perm/Caveats.java");
           assertThat(Files.exists(docFile)).isTrue();
           assertThat(Files.exists(rtFile)).isTrue();
           assertThat(Files.exists(ipFile)).isTrue();
           assertThat(Files.exists(caveatsFile)).isTrue();
           String doc = Files.readString(docFile);
           assertThat(doc).contains("Generated by AuthxCodegen at ");
           assertThat(doc).contains("FOLDER(\"folder\", \"folder\")");
       }
   }
   ```

   (Note: `generate(...)` takes a `SchemaClient` directly — see impl — which is what the `AuthxClient`-taking overload delegates to.)

2. **Run:** `./gradlew test --tests AuthxCodegenTest` — fails.

3. **Implement** `src/main/java/com/authx/sdk/AuthxCodegen.java`. Base: the 386-line historical file on `feature/write-listener-api` @ `195eb75`. Key deltas from history:
   - `emitTypeClass` now takes `Map<String, List<SubjectType>> relationSubjectTypes` and emits varargs `String... sts` + `subjectTypes()` override.
   - Strip the old javadoc example that referenced removed APIs (`toUser(...)` / `findByUser(...)`).
   - Add a `generate(SchemaClient, ...)` overload so tests don't need a real `AuthxClient`.

   ```java
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
    *   <li>{@code XxxType.java} — {@code Rel} enum (with {@code subjectTypes()}
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
           LOG.log(System.Logger.Level.INFO, "AuthxCodegen: generating for {0} resource types", types.size());

           for (String type : types) {
               Set<String> relations = schema.relationsOf(type);
               Set<String> permissions = schema.permissionsOf(type);
               if (relations.isEmpty() && permissions.isEmpty()) continue;

               var relSTs = schema.allSubjectTypes(type);
               String file = emitTypeClass(type, relations, permissions, relSTs, packageName);
               Path out = basePkgDir.resolve(toPascalCase(type) + ".java");
               Files.writeString(out, file);
               LOG.log(System.Logger.Level.INFO, "  Generated: {0}", out);
           }

           Path resourceTypesPath = basePkgDir.resolve("ResourceTypes.java");
           Files.writeString(resourceTypesPath, emitResourceTypes(packageName, types));
           LOG.log(System.Logger.Level.INFO, "  Generated: {0}", resourceTypesPath);

           Set<String> caveatNames = schema.getCaveatNames();
           if (!caveatNames.isEmpty()) {
               for (String name : caveatNames) {
                   var def = schema.getCaveat(name);
                   if (def == null) continue;
                   String src = emitCaveatClass(def.name(), def.parameters(),
                           def.expression(), def.comment(), packageName);
                   Path out = basePkgDir.resolve(toPascalCase(name) + ".java");
                   Files.writeString(out, src);
                   LOG.log(System.Logger.Level.INFO, "  Generated caveat: {0}", out);
               }
               Path caveatsPath = basePkgDir.resolve("Caveats.java");
               Files.writeString(caveatsPath, emitCaveats(packageName, caveatNames));
               LOG.log(System.Logger.Level.INFO, "  Generated: {0}", caveatsPath);
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
   ```

4. **Run:** `./gradlew test --tests AuthxCodegenTest` — passes.
5. **Run:** `./gradlew compileJava`.
6. **Commit:** `git add src/main/java/com/authx/sdk/AuthxCodegen.java src/test/java/com/authx/sdk/AuthxCodegenTest.java && git commit -m "feat(codegen): AuthxCodegen emitting subjectTypes-aware enums + caveats (req-6)"`.

---

## Task T009: Regenerate `test-app/schema/*.java` [SR:req-7]

**Files:**
- Overwrite: `test-app/src/main/java/com/authx/testapp/schema/Department.java`, `Document.java`, `Folder.java`, `Group.java`, `Organization.java`, `ResourceTypes.java`, `Space.java`
- Create: `test-app/src/main/java/com/authx/testapp/schema/User.java` (was missing)
- Create: `src/test/java/com/authx/sdk/AuthxCodegenRegenerationIT.java` — integration test that runs codegen against a fake schema matching `deploy/schema.zed` and asserts expected values.

**Steps:**

1. **Create the regeneration integration test** that feeds a hand-built `SchemaCache` matching `deploy/schema.zed`. (The test's value: when schema changes, rerun and snapshot the output; it also protects the generator from regressions.)

   ```java
   // src/test/java/com/authx/sdk/AuthxCodegenRegenerationIT.java
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

   class AuthxCodegenRegenerationIT {

       /**
        * Feeds AuthxCodegen the exact deploy/schema.zed layout and checks
        * the generated Document.java carries the correct SubjectTypes.
        */
       @Test void regeneratesDocumentWithSubjectTypes(@TempDir Path tmp) throws Exception {
           var user = List.of(SubjectType.of("user"));
           var userOrGroupOrDept = List.of(
                   SubjectType.of("user"),
                   SubjectType.of("group", "member"),
                   SubjectType.of("department", "all_members"));
           var userOrGroupOrDeptOrWildcard = List.of(
                   SubjectType.of("user"),
                   SubjectType.of("group", "member"),
                   SubjectType.of("department", "all_members"),
                   SubjectType.wildcard("user"));
           var wildcardOnly = List.of(SubjectType.wildcard("user"));

           var cache = new SchemaCache();
           cache.updateFromMap(Map.of(
                   "document", new SchemaCache.DefinitionCache(
                           Set.of("folder", "space", "owner", "editor", "commenter", "viewer",
                                   "link_viewer", "link_editor"),
                           Set.of("manage", "edit", "comment", "view", "delete", "share"),
                           Map.of(
                                   "folder",     List.of(SubjectType.of("folder")),
                                   "space",      List.of(SubjectType.of("space")),
                                   "owner",      user,
                                   "editor",     userOrGroupOrDept,
                                   "commenter",  userOrGroupOrDept,
                                   "viewer",     userOrGroupOrDeptOrWildcard,
                                   "link_viewer", wildcardOnly,
                                   "link_editor", wildcardOnly))));

           var schema = new SchemaClient(cache);
           AuthxCodegen.generate(schema, tmp.toString(), "com.example");

           String doc = Files.readString(tmp.resolve("com/example/Document.java"));
           assertThat(doc).contains("FOLDER(\"folder\", \"folder\")");
           assertThat(doc).contains("VIEWER(\"viewer\", \"user\", \"group#member\", \"department#all_members\", \"user:*\")");
           assertThat(doc).contains("LINK_VIEWER(\"link_viewer\", \"user:*\")");
           assertThat(doc).contains("OWNER(\"owner\", \"user\")");
       }
   }
   ```

2. **Run:** `./gradlew test --tests AuthxCodegenRegenerationIT` — passes (exercises emitter against the real schema shape).

3. **Regenerate the files**. Two options — pick (A) for this task; (B) is a future Gradle wrapper (non-goal):

   **(A) One-shot generator script** (throwaway, not committed):
   ```bash
   # Create scratch Java main class that builds the schema cache identical to the IT above
   # and calls generate(schema, "test-app/src/main/java", "com.authx.testapp.schema").
   # Or connect to a running SpiceDB with deploy/schema.zed applied.
   ```

   Simplest: **commit a JUnit `@Disabled` regeneration tool** under `src/test/java/com/authx/sdk/RegenerateTestAppSchemaTool.java`:

   ```java
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
    * Mirrors {@code deploy/schema.zed}. Enable the {@code @Disabled} guard to re-run.
    *
    * <p>Keeping this as a checked-in tool (vs a throwaway script) means the
    * "one source of truth for test-app schema" is visible in code review.
    */
   @Disabled("manual regeneration tool; remove the @Disabled to regenerate")
   class RegenerateTestAppSchemaTool {

       @Test void regenerate() throws Exception {
           var user = List.of(SubjectType.of("user"));
           var userOrDept = List.of(SubjectType.of("user"), SubjectType.of("department", "all_members"));
           var userOrGroupMember = List.of(SubjectType.of("user"), SubjectType.of("group", "member"));
           var userOrGroupOrDept = List.of(
                   SubjectType.of("user"),
                   SubjectType.of("group", "member"),
                   SubjectType.of("department", "all_members"));
           var userOrGroupOrDeptOrWildcard = List.of(
                   SubjectType.of("user"),
                   SubjectType.of("group", "member"),
                   SubjectType.of("department", "all_members"),
                   SubjectType.wildcard("user"));
           var wildcardOnly = List.of(SubjectType.wildcard("user"));

           var cache = new SchemaCache();
           cache.updateFromMap(Map.ofEntries(
                   Map.entry("user", new SchemaCache.DefinitionCache(Set.of(), Set.of(), Map.of())),
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
                           Map.of("admin", user, "member", userOrDept))),
                   Map.entry("space", new SchemaCache.DefinitionCache(
                           Set.of("org", "owner", "admin", "member", "viewer"),
                           Set.of("manage", "edit", "view"),
                           Map.of(
                                   "org",    List.of(SubjectType.of("organization")),
                                   "owner",  user,
                                   "admin",  userOrGroupMember,
                                   "member", userOrGroupOrDept,
                                   "viewer", userOrGroupOrDeptOrWildcard))),
                   Map.entry("folder", new SchemaCache.DefinitionCache(
                           Set.of("space", "parent", "owner", "editor", "commenter", "viewer"),
                           Set.of("manage", "edit", "comment", "view", "create_child"),
                           Map.of(
                                   "space",     List.of(SubjectType.of("space")),
                                   "parent",    List.of(SubjectType.of("folder")),
                                   "owner",     user,
                                   "editor",    userOrGroupOrDept,
                                   "commenter", userOrGroupOrDept,
                                   "viewer",    userOrGroupOrDeptOrWildcard))),
                   Map.entry("document", new SchemaCache.DefinitionCache(
                           Set.of("folder", "space", "owner", "editor", "commenter", "viewer",
                                   "link_viewer", "link_editor"),
                           Set.of("manage", "edit", "comment", "view", "delete", "share"),
                           Map.of(
                                   "folder",     List.of(SubjectType.of("folder")),
                                   "space",      List.of(SubjectType.of("space")),
                                   "owner",      user,
                                   "editor",     userOrGroupOrDept,
                                   "commenter",  userOrGroupOrDept,
                                   "viewer",     userOrGroupOrDeptOrWildcard,
                                   "link_viewer", wildcardOnly,
                                   "link_editor", wildcardOnly)))));

           AuthxCodegen.generate(new SchemaClient(cache),
                   "test-app/src/main/java", "com.authx.testapp.schema");
       }
   }
   ```

4. **Run the regeneration** — remove `@Disabled` locally, run the test once, re-add `@Disabled`:
   ```bash
   # Toggle @Disabled off, run, toggle back on
   sed -i '' 's|@Disabled("manual regeneration tool; remove the @Disabled to regenerate")|// RE-ENABLE: @Disabled("manual regeneration tool; remove the @Disabled to regenerate")|' src/test/java/com/authx/sdk/RegenerateTestAppSchemaTool.java
   ./gradlew test --tests RegenerateTestAppSchemaTool
   sed -i '' 's|// RE-ENABLE: @Disabled|@Disabled|' src/test/java/com/authx/sdk/RegenerateTestAppSchemaTool.java
   ```

5. **Verify business code still compiles:**
   ```bash
   ./gradlew :test-app:compileJava
   ./gradlew :test-app:test
   ```

6. **Verify subjectTypes is visible at runtime** — add assertion to `test-app/src/test/java/com/authx/testapp/schema/SubjectTypeRegressionTest.java`:

   ```java
   package com.authx.testapp.schema;

   import com.authx.sdk.model.SubjectType;
   import org.junit.jupiter.api.Test;
   import static org.assertj.core.api.Assertions.assertThat;

   class SubjectTypeRegressionTest {

       @Test void folderRelationIsSingleTypedFolder() {
           assertThat(Document.Rel.FOLDER.subjectTypes())
                   .containsExactly(SubjectType.of("folder"));
       }

       @Test void viewerRelationIsUserGroupDeptWildcard() {
           assertThat(Document.Rel.VIEWER.subjectTypes())
                   .containsExactly(
                           SubjectType.of("user"),
                           SubjectType.of("group", "member"),
                           SubjectType.of("department", "all_members"),
                           SubjectType.wildcard("user"));
       }

       @Test void linkViewerIsWildcardOnly() {
           assertThat(Document.Rel.LINK_VIEWER.subjectTypes())
                   .containsExactly(SubjectType.wildcard("user"));
       }

       @Test void legacyEnumsWithoutSubjectTypesReturnEmpty() {
           // N/A — all regenerated enums should now have subjectTypes
       }
   }
   ```

7. **Run:** `./gradlew :test-app:test`.
8. **Commit:** `git add test-app/src/main/java/com/authx/testapp/schema/ test-app/src/test/java/com/authx/testapp/schema/SubjectTypeRegressionTest.java src/test/java/com/authx/sdk/RegenerateTestAppSchemaTool.java && git commit -m "feat(test-app): regenerate schema classes with subjectTypes metadata (req-7)"`.

---

### Phase 1 gate

```bash
./gradlew test -x :test-app:test -x :cluster-test:test   # SDK green
./gradlew :test-app:test                                  # test-app still green
```

**Open PR-A:** `feature/pr-a-codegen-restore` → `main`.

---

# Phase 2: PR-B — Subject validation fail-fast

After PR-A merges:
```bash
git checkout main && git pull
git checkout -b feature/pr-b-subject-validation
```

## Task T010: `SchemaCache.validateSubject` — write failing test [SR:req-8]

**Files:**
- Create: `src/test/java/com/authx/sdk/cache/SchemaCacheValidateSubjectTest.java`

**Steps:**

1. **Write test** covering the matrix of {allowed typed, allowed relation'd, allowed wildcard, disallowed type, empty schema}:

   ```java
   package com.authx.sdk.cache;

   import com.authx.sdk.exception.InvalidRelationException;
   import com.authx.sdk.model.SubjectType;
   import org.junit.jupiter.api.BeforeEach;
   import org.junit.jupiter.api.Test;

   import java.util.List;
   import java.util.Map;
   import java.util.Set;

   import static org.assertj.core.api.Assertions.*;

   class SchemaCacheValidateSubjectTest {

       private SchemaCache cache;

       @BeforeEach void setUp() {
           cache = new SchemaCache();
           cache.updateFromMap(Map.of(
                   "document", new SchemaCache.DefinitionCache(
                           Set.of("folder", "viewer"),
                           Set.of("view"),
                           Map.of(
                                   "folder", List.of(SubjectType.of("folder")),
                                   "viewer", List.of(
                                           SubjectType.of("user"),
                                           SubjectType.of("group", "member"),
                                           SubjectType.wildcard("user"))))));
       }

       @Test void acceptsTypedSubject() {
           cache.validateSubject("document", "folder", "folder:f-1");
           cache.validateSubject("document", "viewer", "user:alice");
       }

       @Test void acceptsSubjectWithRelation() {
           cache.validateSubject("document", "viewer", "group:eng#member");
       }

       @Test void acceptsWildcard() {
           cache.validateSubject("document", "viewer", "user:*");
       }

       @Test void rejectsWrongType() {
           assertThatThrownBy(() ->
                   cache.validateSubject("document", "folder", "user:alice"))
                   .isInstanceOf(InvalidRelationException.class)
                   .hasMessageContaining("document.folder")
                   .hasMessageContaining("[folder]");
       }

       @Test void rejectsWildcardWhenNotDeclared() {
           assertThatThrownBy(() ->
                   cache.validateSubject("document", "folder", "folder:*"))
                   .isInstanceOf(InvalidRelationException.class);
       }

       @Test void rejectsWrongSubjectRelation() {
           assertThatThrownBy(() ->
                   cache.validateSubject("document", "viewer", "group:eng#admin"))
                   .isInstanceOf(InvalidRelationException.class)
                   .hasMessageContaining("group#member");
       }

       @Test void emptySchemaIsFailOpen() {
           var empty = new SchemaCache();
           // no throw
           empty.validateSubject("anything", "anything", "x:y");
       }

       @Test void unknownResourceTypeIsFailOpen() {
           // Let other validators catch this; do not double-reject.
           cache.validateSubject("widget", "owner", "user:alice");
       }

       @Test void unknownRelationIsFailOpen() {
           // Same reasoning.
           cache.validateSubject("document", "unknown_rel", "user:alice");
       }

       @Test void rejectsRefWithoutColon() {
           assertThatThrownBy(() ->
                   cache.validateSubject("document", "folder", "f-1"))
                   .isInstanceOf(InvalidRelationException.class)
                   .hasMessageContaining("type:id");
       }
   }
   ```

2. **Run:** `./gradlew test --tests SchemaCacheValidateSubjectTest` — fails (method missing).
3. No commit yet — implementation in T011.

---

## Task T011: Implement `SchemaCache.validateSubject` [SR:req-8]

**Files:**
- Modify: `src/main/java/com/authx/sdk/cache/SchemaCache.java`

**Steps:**

1. **Add method** inside `SchemaCache` (below the getters):

   ```java
   /**
    * Validate that {@code subjectRef} is an allowed subject for
    * {@code resourceType.relation}. Fail-open when the schema is not
    * loaded or the type/relation is unknown — other validators cover those.
    *
    * @throws com.authx.sdk.exception.InvalidRelationException when the
    *         subject type is declared but does not match any allowed
    *         {@link SubjectType} on the relation.
    */
   public void validateSubject(String resourceType, String relation, String subjectRef) {
       var d = defs.get().get(resourceType);
       if (d == null) return;                     // fail-open
       var allowed = d.relationSubjectTypes().get(relation);
       if (allowed == null || allowed.isEmpty()) return;  // fail-open

       int colon = subjectRef.indexOf(':');
       if (colon < 0) {
           throw new com.authx.sdk.exception.InvalidRelationException(
                   "Invalid subject reference \"" + subjectRef
                           + "\": expected \"type:id\" or \"type:id#relation\" form");
       }
       String type = subjectRef.substring(0, colon);
       String idPart = subjectRef.substring(colon + 1);
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
               if (st.wildcard()) return;
               continue;
           }
           if (st.wildcard()) continue;
           String declared = st.relation() == null ? "" : st.relation();
           if (declared.equals(subRelation)) return;
       }

       String shapes = allowed.stream()
               .map(SubjectType::toRef)
               .distinct()
               .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
       throw new com.authx.sdk.exception.InvalidRelationException(
               resourceType + "." + relation + " does not accept subject \""
                       + subjectRef + "\". Allowed subject types: " + shapes
                       + ". Check your schema, or use a different relation.");
   }
   ```

2. **Run:** `./gradlew test --tests SchemaCacheValidateSubjectTest` — passes.
3. **Run:** `./gradlew compileJava`.
4. **Commit:** `git add -u src/main/java/com/authx/sdk/cache/SchemaCache.java src/test/java/com/authx/sdk/cache/SchemaCacheValidateSubjectTest.java && git commit -m "feat(cache): SchemaCache.validateSubject with allowed-shape error messages (req-8)"`.

---

## Task T012: Wire `SchemaCache` through `ResourceFactory` / `ResourceHandle` + `GrantAction` validation [SR:req-8]

**Files:**
- Modify: `src/main/java/com/authx/sdk/AuthxClient.java` (inject SchemaCache into `ResourceFactory` creation)
- Modify: `src/main/java/com/authx/sdk/ResourceFactory.java`
- Modify: `src/main/java/com/authx/sdk/ResourceHandle.java`
- Modify: `src/main/java/com/authx/sdk/action/GrantAction.java`
- Create: `src/test/java/com/authx/sdk/action/GrantActionValidationTest.java`

**Steps:**

1. **Write failing test** `src/test/java/com/authx/sdk/action/GrantActionValidationTest.java`:

   ```java
   package com.authx.sdk.action;

   import com.authx.sdk.cache.SchemaCache;
   import com.authx.sdk.exception.InvalidRelationException;
   import com.authx.sdk.model.GrantResult;
   import com.authx.sdk.model.SubjectType;
   import com.authx.sdk.transport.SdkTransport;
   import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
   import org.junit.jupiter.api.Test;

   import java.util.List;
   import java.util.Map;
   import java.util.Set;
   import java.util.concurrent.atomic.AtomicInteger;

   import static org.assertj.core.api.Assertions.*;

   class GrantActionValidationTest {

       private SchemaCache schemaFor(String type, String relation, List<SubjectType> sts) {
           var c = new SchemaCache();
           c.updateFromMap(Map.of(type, new SchemaCache.DefinitionCache(
                   Set.of(relation), Set.of(), Map.of(relation, sts))));
           return c;
       }

       private SdkTransport recordingTransport(AtomicInteger calls) {
           return new com.authx.sdk.transport.InMemoryTransport() {
               @Override public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
                   calls.incrementAndGet();
                   return super.writeRelationships(updates);
               }
           };
       }

       @Test void rejectsWrongSubjectType() {
           var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
           var calls = new AtomicInteger();
           var action = new GrantAction("document", "d-1", recordingTransport(calls),
                   new String[]{"folder"}, cache);
           assertThatThrownBy(() -> action.to("user:alice"))
                   .isInstanceOf(InvalidRelationException.class)
                   .hasMessageContaining("[folder]");
           assertThat(calls).hasValue(0);
       }

       @Test void acceptsAllowedSubjectType() {
           var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
           var calls = new AtomicInteger();
           var action = new GrantAction("document", "d-1", recordingTransport(calls),
                   new String[]{"folder"}, cache);
           action.to("folder:f-1");
           assertThat(calls).hasValue(1);
       }

       @Test void nullSchemaCacheIsFailOpen() {
           var calls = new AtomicInteger();
           var action = new GrantAction("document", "d-1", recordingTransport(calls),
                   new String[]{"folder"}, null);
           action.to("user:alice"); // no throw
           assertThat(calls).hasValue(1);
       }

       @Test void emptySchemaCacheIsFailOpen() {
           var calls = new AtomicInteger();
           var action = new GrantAction("document", "d-1", recordingTransport(calls),
                   new String[]{"folder"}, new SchemaCache());
           action.to("user:alice"); // no throw
           assertThat(calls).hasValue(1);
       }
   }
   ```

2. **Run:** `./gradlew test --tests GrantActionValidationTest` — fails (ctor overload missing).

3. **Modify `GrantAction`** — add nullable `SchemaCache` field and validation. Keep the old constructor as deprecated delegating to the new one:

   ```java
   import com.authx.sdk.cache.SchemaCache;

   private final SchemaCache schemaCache;  // nullable

   public GrantAction(String resourceType, String resourceId, SdkTransport transport,
                      String[] relations) {
       this(resourceType, resourceId, transport, relations, null);
   }

   public GrantAction(String resourceType, String resourceId, SdkTransport transport,
                      String[] relations, SchemaCache schemaCache) {
       this.resourceType = resourceType;
       this.resourceId = resourceId;
       this.transport = transport;
       this.relations = relations;
       this.schemaCache = schemaCache;
   }

   // In writeRelationships(...), before building updates:
   if (schemaCache != null) {
       for (String rel : relations) {
           for (SubjectRef sub : subjects) {
               schemaCache.validateSubject(resourceType, rel, sub.toRefString());
           }
       }
   }
   ```

4. **Modify `ResourceHandle`** — add field + pass-through ctor that forwards the cache to `new GrantAction(...)`. Keep existing ctor delegating with `null`.

5. **Modify `ResourceFactory`** — same pattern: add `SchemaCache schemaCache` field, new ctor taking it, `resource(...)` passes it to `ResourceHandle`. Keep old ctor delegating with `null`.

6. **Modify `AuthxClient`** — when constructing factories in `on(String)`, pass `schemaClient` backing cache. Add a package-private method on `SchemaClient` to expose the underlying cache, OR keep a second field `schemaCache` on `AuthxClient`:

   - Easiest: give `AuthxClient` a `SchemaCache schemaCache` field (package-private constructor param), and mint it alongside `SchemaClient`. Update `AuthxClientBuilder` to construct `SchemaCache` once and hand both to the client.

   ```java
   // AuthxClient field:
   private final SchemaCache schemaCache;

   // Updated constructor signature:
   AuthxClient(SdkTransport transport, SdkInfrastructure infra, SdkObservability observability,
               SdkConfig config, HealthProbe healthProbe, SchemaClient schemaClient,
               SchemaCache schemaCache) {
       ...
       this.schemaCache = schemaCache;  // nullable
   }

   // In on(String):
   public ResourceFactory on(String resourceType) {
       return factories.computeIfAbsent(resourceType, type ->
               new ResourceFactory(type, transport, infra.asyncExecutor(), schemaCache));
   }

   // In inMemory(): pass null for both schemaClient and schemaCache.
   ```

7. **Update `AuthxClientBuilder.build()`** — pass the `schemaCache` instance (the same one the `SchemaClient` wraps) into the `AuthxClient` constructor.

8. **Run:** `./gradlew test --tests GrantActionValidationTest` — passes.
9. **Run:** `./gradlew compileJava` + `./gradlew test -x :test-app:test -x :cluster-test:test`.
10. **Commit:** `git add -u && git add src/test/java/com/authx/sdk/action/GrantActionValidationTest.java && git commit -m "feat(sdk): GrantAction validates subject types against schema (req-8)"`.

---

## Task T013: `RevokeAction` subject validation [SR:req-8]

**Files:**
- Modify: `src/main/java/com/authx/sdk/ResourceHandle.java` (pass cache to `new RevokeAction`)
- Modify: `src/main/java/com/authx/sdk/action/RevokeAction.java`
- Create: `src/test/java/com/authx/sdk/action/RevokeActionValidationTest.java`

**Steps:**

1. **Write failing test** — mirror `GrantActionValidationTest` but on `RevokeAction.from(...)`:

   ```java
   package com.authx.sdk.action;

   import com.authx.sdk.cache.SchemaCache;
   import com.authx.sdk.exception.InvalidRelationException;
   import com.authx.sdk.model.SubjectType;
   import com.authx.sdk.transport.InMemoryTransport;
   import org.junit.jupiter.api.Test;

   import java.util.List;
   import java.util.Map;
   import java.util.Set;

   import static org.assertj.core.api.Assertions.assertThatThrownBy;

   class RevokeActionValidationTest {

       @Test void rejectsWrongSubjectType() {
           var cache = new SchemaCache();
           cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                   Set.of("folder"), Set.of(),
                   Map.of("folder", List.of(SubjectType.of("folder"))))));
           var action = new RevokeAction("document", "d-1", new InMemoryTransport(),
                   new String[]{"folder"}, cache);
           assertThatThrownBy(() -> action.from("user:alice"))
                   .isInstanceOf(InvalidRelationException.class);
       }

       @Test void nullCacheIsFailOpen() {
           var action = new RevokeAction("document", "d-1", new InMemoryTransport(),
                   new String[]{"folder"}, null);
           action.from("user:alice"); // no throw
       }
   }
   ```

2. **Run:** fails.

3. **Implement** — same pattern as `GrantAction`: add `SchemaCache schemaCache` field + new ctor + validation loop before `deleteRelationships`.

4. **Run:** passes.
5. **Run:** `./gradlew compileJava` + SDK tests.
6. **Commit:** `git add -u && git add src/test/java/com/authx/sdk/action/RevokeActionValidationTest.java && git commit -m "feat(sdk): RevokeAction validates subject types (req-8)"`.

---

## Task T014: `BatchGrantAction` + `BatchRevokeAction` subject validation [SR:req-8]

**Files:**
- Modify: `src/main/java/com/authx/sdk/ResourceHandle.java` (pass cache to batch actions via `BatchBuilder`)
- Modify: `src/main/java/com/authx/sdk/action/BatchGrantAction.java`
- Modify: `src/main/java/com/authx/sdk/action/BatchRevokeAction.java`
- Modify: `src/main/java/com/authx/sdk/action/BatchBuilder.java` (propagate `SchemaCache` into the actions it creates)
- Create: `src/test/java/com/authx/sdk/action/BatchValidationTest.java`

**Steps:**

1. **Write failing test** that builds a batch with one valid + one invalid subject and asserts the invalid one triggers `InvalidRelationException` at `.to(...)` time (before `commit()`):

   ```java
   package com.authx.sdk.action;

   import com.authx.sdk.cache.SchemaCache;
   import com.authx.sdk.exception.InvalidRelationException;
   import com.authx.sdk.model.SubjectType;
   import com.authx.sdk.transport.InMemoryTransport;
   import org.junit.jupiter.api.BeforeEach;
   import org.junit.jupiter.api.Test;

   import java.util.List;
   import java.util.Map;
   import java.util.Set;

   import static org.assertj.core.api.Assertions.assertThatThrownBy;

   class BatchValidationTest {

       SchemaCache cache;

       @BeforeEach void setUp() {
           cache = new SchemaCache();
           cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                   Set.of("folder"), Set.of(),
                   Map.of("folder", List.of(SubjectType.of("folder"))))));
       }

       @Test void batchGrantRejectsWrongType() {
           var b = new BatchBuilder(new InMemoryTransport(), cache);
           assertThatThrownBy(() ->
                   b.on("document").resource("d-1").grant("folder").to("user:alice"))
                   .isInstanceOf(InvalidRelationException.class);
       }

       @Test void batchRevokeRejectsWrongType() {
           var b = new BatchBuilder(new InMemoryTransport(), cache);
           assertThatThrownBy(() ->
                   b.on("document").resource("d-1").revoke("folder").from("user:alice"))
                   .isInstanceOf(InvalidRelationException.class);
       }
   }
   ```

   (If `BatchBuilder`'s existing API doesn't include the `.on(type).resource(id).grant(...)` chain, adjust to the actual entry point — inspect `BatchBuilder` first and replicate a realistic call.)

2. **Run:** fails.

3. **Implement** same pattern: add `SchemaCache schemaCache` param through `BatchBuilder` → `BatchGrantAction` / `BatchRevokeAction`; validate in each `to(...)` / `from(...)` method before calling `batch.addUpdate(...)`.

4. **Run:** passes.
5. **Run:** `./gradlew test -x :test-app:test -x :cluster-test:test` — full SDK green.
6. **Commit:** `git add -u && git add src/test/java/com/authx/sdk/action/BatchValidationTest.java && git commit -m "feat(sdk): Batch grant/revoke validate subject types (req-8)"`.

---

## Task T015: Typed-chain inheritance smoke test [P] [SR:req-9]

**Files:**
- Create: `src/test/java/com/authx/sdk/TypedGrantActionValidationTest.java`

**Steps:**

1. **Write test** verifying that the typed chain (`TypedGrantAction` → underlying `GrantAction`) picks up validation without any extra code:

   ```java
   package com.authx.sdk;

   import com.authx.sdk.cache.SchemaCache;
   import com.authx.sdk.exception.InvalidRelationException;
   import com.authx.sdk.model.Permission;
   import com.authx.sdk.model.Relation;
   import com.authx.sdk.model.SubjectType;
   import com.authx.sdk.transport.InMemoryTransport;
   import org.junit.jupiter.api.Test;

   import java.lang.reflect.Field;
   import java.util.List;
   import java.util.Map;
   import java.util.Set;

   import static org.assertj.core.api.Assertions.assertThatThrownBy;

   class TypedGrantActionValidationTest {

       enum DocRel implements Relation.Named {
           FOLDER("folder"), VIEWER("viewer");
           private final String v; DocRel(String v) { this.v = v; }
           @Override public String relationName() { return v; }
       }
       enum DocPerm implements Permission.Named {
           VIEW("view");
           private final String v; DocPerm(String v) { this.v = v; }
           @Override public String permissionName() { return v; }
       }

       @Test void typedGrantInheritsSubjectValidation() throws Exception {
           var cache = new SchemaCache();
           cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                   Set.of("folder"), Set.of(),
                   Map.of("folder", List.of(SubjectType.of("folder"))))));
           var factory = new ResourceFactory("document", new InMemoryTransport(), Runnable::run);
           // Inject the cache (package-private setter / ctor per T012).
           Field f = ResourceFactory.class.getDeclaredField("schemaCache");
           f.setAccessible(true);
           f.set(factory, cache);

           @SuppressWarnings("rawtypes")
           var type = ResourceType.of("document", DocRel.class, DocPerm.class);
           var entry = new TypedResourceEntry<>(factory, type);
           assertThatThrownBy(() -> entry.select("d-1").grant(DocRel.FOLDER).to("user:alice"))
                   .isInstanceOf(InvalidRelationException.class);
       }
   }
   ```

2. **Run:** `./gradlew test --tests TypedGrantActionValidationTest` — passes (no production code change required — validation sits in the leaf `GrantAction`).
3. **Commit:** `git add src/test/java/com/authx/sdk/TypedGrantActionValidationTest.java && git commit -m "test(sdk): typed chain inherits subject validation (req-9)"`.

---

### Phase 2 gate

```bash
./gradlew test -x :test-app:test -x :cluster-test:test   # SDK green
./gradlew :test-app:test                                  # test-app still green
```

**Open PR-B:** `feature/pr-b-subject-validation` → `main`.

---

# Phase 3: PR-C — Single-type inference + typed overloads + test-app migration

After PR-B merges:
```bash
git checkout main && git pull
git checkout -b feature/pr-c-typed-subject-overloads
```

## Task T016: Single-type inference helper on `SubjectType` [SR:req-10]

**Files:**
- Modify: `src/main/java/com/authx/sdk/model/SubjectType.java`
- Modify: `src/test/java/com/authx/sdk/model/SubjectTypeTest.java`

**Steps:**

1. **Write failing test** — add to `SubjectTypeTest`:

   ```java
   import java.util.List;
   import java.util.Optional;

   @Test void inferSingleTypedAmongMany_returnsEmpty() {
       Optional<SubjectType> inferred = SubjectType.inferSingleType(List.of(
               SubjectType.of("user"),
               SubjectType.of("group", "member")));
       assertThat(inferred).isEmpty();
   }

   @Test void inferSingleTyped_returnsIt() {
       Optional<SubjectType> inferred = SubjectType.inferSingleType(List.of(
               SubjectType.of("folder")));
       assertThat(inferred).contains(SubjectType.of("folder"));
   }

   @Test void inferWithWildcardSiblingIgnoresWildcard() {
       // 常见: viewer: user | user:* — 只有一个"非通配"类型 user，可以推断
       Optional<SubjectType> inferred = SubjectType.inferSingleType(List.of(
               SubjectType.of("user"),
               SubjectType.wildcard("user")));
       assertThat(inferred).contains(SubjectType.of("user"));
   }

   @Test void pureWildcardReturnsEmpty() {
       Optional<SubjectType> inferred = SubjectType.inferSingleType(List.of(
               SubjectType.wildcard("user")));
       assertThat(inferred).isEmpty();
   }

   @Test void emptyListReturnsEmpty() {
       assertThat(SubjectType.inferSingleType(List.of())).isEmpty();
   }
   ```

2. **Run:** fails.

3. **Implement** — add to `SubjectType.java`:

   ```java
   /**
    * If exactly one non-wildcard {@link SubjectType} is declared, return it.
    * Otherwise return empty. Wildcard-only declarations also return empty
    * (you cannot infer a concrete id from a wildcard).
    *
    * <p>Used by {@code GrantAction.to(String id)} to decide whether a bare
    * id can be wrapped into a canonical {@code type:id} subject.
    */
   public static java.util.Optional<SubjectType> inferSingleType(List<SubjectType> candidates) {
       SubjectType sole = null;
       for (SubjectType st : candidates) {
           if (st.wildcard()) continue;
           if (sole != null) return java.util.Optional.empty();
           sole = st;
       }
       return java.util.Optional.ofNullable(sole);
   }
   ```

   (Note: the import of `java.util.List` may need adding at the top of the file.)

4. **Run:** passes.
5. **Run:** `./gradlew compileJava`.
6. **Commit:** `git add -u && git commit -m "feat(model): SubjectType.inferSingleType helper (req-10)"`.

---

## Task T017: `GrantAction.to(String id)` single-type inference [SR:req-10]

**Files:**
- Modify: `src/main/java/com/authx/sdk/action/GrantAction.java`
- Create: `src/test/java/com/authx/sdk/action/GrantActionInferenceTest.java`

**Steps:**

1. **Write failing test:**

   ```java
   package com.authx.sdk.action;

   import com.authx.sdk.cache.SchemaCache;
   import com.authx.sdk.model.SubjectType;
   import com.authx.sdk.transport.InMemoryTransport;
   import org.junit.jupiter.api.Test;

   import java.util.List;
   import java.util.Map;
   import java.util.Set;

   import static org.assertj.core.api.Assertions.*;

   class GrantActionInferenceTest {

       private GrantAction action(String relation, List<SubjectType> sts) {
           var cache = new SchemaCache();
           cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                   Set.of(relation), Set.of(), Map.of(relation, sts))));
           return new GrantAction("document", "d-1", new InMemoryTransport(),
                   new String[]{relation}, cache);
       }

       @Test void singleTypeInferred() {
           var a = action("folder", List.of(SubjectType.of("folder")));
           a.to("f-1"); // no exception — inferred as folder:f-1
       }

       @Test void canonicalStillWorks() {
           var a = action("folder", List.of(SubjectType.of("folder")));
           a.to("folder:f-1");
       }

       @Test void multiTypeRelationThrows() {
           var a = action("viewer", List.of(
                   SubjectType.of("user"),
                   SubjectType.of("group", "member")));
           assertThatThrownBy(() -> a.to("alice"))
                   .isInstanceOf(IllegalArgumentException.class)
                   .hasMessageContaining("ambiguous")
                   .hasMessageContaining("to(ResourceType, id)");
       }

       @Test void wildcardOnlyRelationThrows() {
           var a = action("link_viewer", List.of(SubjectType.wildcard("user")));
           assertThatThrownBy(() -> a.to("alice"))
                   .isInstanceOf(IllegalArgumentException.class)
                   .hasMessageContaining("wildcard");
       }

       @Test void emptySchemaFallsBackToCanonicalParse() {
           var a = new GrantAction("document", "d-1", new InMemoryTransport(),
                   new String[]{"folder"}, null);
           assertThatThrownBy(() -> a.to("alice")) // no colon + no schema → fall back to canonical, which throws
                   .isInstanceOf(IllegalArgumentException.class);
       }
   }
   ```

2. **Run:** fails (inference not yet implemented; existing `.to("alice")` just throws canonical parse error).

3. **Implement** in `GrantAction`:

   - Add a new method with explicit single-string signature (more specific than `to(String...)`):

     ```java
     /**
      * Single-id form with single-type inference. If exactly one
      * non-wildcard subject type is declared on every selected relation
      * (same type across all relations), wrap {@code id} into the
      * canonical {@code type:id} form. If {@code id} already contains a
      * {@code :} it is treated as canonical. Otherwise the call fails
      * with a descriptive {@link IllegalArgumentException}.
      */
     public GrantResult to(String id) {
         // 1) If it looks canonical, defer to the existing varargs path
         if (id.indexOf(':') >= 0) {
             return to(new String[]{id});
         }
         // 2) Need schemaCache to infer
         if (schemaCache == null) {
             return to(new String[]{id}); // falls through to canonical parse which throws
         }
         SubjectType inferred = null;
         for (String rel : relations) {
             var sts = schemaCache.getSubjectTypes(resourceType, rel);
             var single = SubjectType.inferSingleType(sts);
             if (single.isEmpty()) {
                 if (sts.isEmpty() || sts.stream().allMatch(SubjectType::wildcard)) {
                     throw new IllegalArgumentException(
                             resourceType + "." + rel + " only accepts wildcards; use toWildcard(ResourceType)");
                 }
                 throw new IllegalArgumentException(
                         "ambiguous subject type for " + resourceType + "." + rel
                                 + " (allowed: " + sts.stream()
                                         .map(SubjectType::toRef)
                                         .collect(java.util.stream.Collectors.joining(", ", "[", "]"))
                                 + "); use to(ResourceType, id) instead");
             }
             if (inferred == null) inferred = single.get();
             else if (!inferred.type().equals(single.get().type())) {
                 throw new IllegalArgumentException(
                         "cannot infer single subject type across " + relations.length
                                 + " relations with differing declared types; use to(ResourceType, id)");
             }
         }
         String canonical = (inferred != null ? inferred.type() : "user") + ":" + id;
         return to(new String[]{canonical});
     }
     ```

   - Note: also need `import com.authx.sdk.model.SubjectType;`.

4. **Run:** passes.
5. **Run:** `./gradlew compileJava` + SDK tests.
6. **Commit:** `git add -u src/main/java/com/authx/sdk/action/GrantAction.java src/test/java/com/authx/sdk/action/GrantActionInferenceTest.java && git commit -m "feat(sdk): GrantAction.to(id) single-type inference (req-10)"`.

---

## Task T018: `GrantAction.to(ResourceType, String id)` typed overload [SR:req-11]

**Files:**
- Modify: `src/main/java/com/authx/sdk/action/GrantAction.java`
- Modify: `src/test/java/com/authx/sdk/action/GrantActionInferenceTest.java` (extend)

**Steps:**

1. **Add tests:**

   ```java
   @Test void typedOverloadAcceptsAnyRegisteredType() {
       // viewer allows user | group#member | user:*
       var cache = new SchemaCache();
       cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
               Set.of("viewer"), Set.of(),
               Map.of("viewer", List.of(
                       SubjectType.of("user"),
                       SubjectType.of("group", "member"))))));
       var a = new GrantAction("document", "d-1", new InMemoryTransport(),
               new String[]{"viewer"}, cache);
       var userType = com.authx.sdk.ResourceType.of("user",
               com.authx.sdk.model.RelationNamedSubjectTypesTest.LegacyRel.class,
               com.authx.sdk.model.PermissionNamedTest.PermEnum.class);
       a.to(userType, "alice"); // no throw
   }

   @Test void typedOverloadRejectsNotAllowed() {
       var cache = new SchemaCache();
       cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
               Set.of("folder"), Set.of(),
               Map.of("folder", List.of(SubjectType.of("folder"))))));
       var a = new GrantAction("document", "d-1", new InMemoryTransport(),
               new String[]{"folder"}, cache);
       var userType = com.authx.sdk.ResourceType.of("user",
               com.authx.sdk.model.RelationNamedSubjectTypesTest.LegacyRel.class,
               com.authx.sdk.model.PermissionNamedTest.PermEnum.class);
       assertThatThrownBy(() -> a.to(userType, "alice"))
               .isInstanceOf(com.authx.sdk.exception.InvalidRelationException.class);
   }
   ```

   (Depends on a test-only `PermissionNamedTest.PermEnum` you can add as a nested enum in a new helper test class, or simply use `Document.Perm` from test-app if accessible. Simpler: define a tiny enum inline with `<R, P>` raw types, similar to T015.)

2. **Run:** fails.

3. **Implement:**

   ```java
   import com.authx.sdk.ResourceType;
   import com.authx.sdk.model.Permission;
   import com.authx.sdk.model.Relation;

   /** Typed subject form: {@code grant(...).to(User.TYPE, "alice")}. */
   public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
   GrantResult to(ResourceType<R, P> subjectType, String id) {
       return to(new String[]{subjectType.name() + ":" + id});
   }
   ```

4. **Run:** passes (validation flows naturally through `to(String...)` → `writeRelationships` → `schemaCache.validateSubject`).
5. **Commit:** `git add -u && git commit -m "feat(sdk): GrantAction.to(ResourceType, id) typed overload (req-11)"`.

---

## Task T019: `GrantAction.to(ResourceType, String id, String relation)` subject-relation overload [SR:req-11]

**Files:**
- Modify: `src/main/java/com/authx/sdk/action/GrantAction.java`
- Modify: `src/test/java/com/authx/sdk/action/GrantActionInferenceTest.java`

**Steps:**

1. **Add test:**

   ```java
   @Test void typedOverloadWithSubRelation() {
       var cache = new SchemaCache();
       cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
               Set.of("viewer"), Set.of(),
               Map.of("viewer", List.of(SubjectType.of("group", "member"))))));
       var a = new GrantAction("document", "d-1", new InMemoryTransport(),
               new String[]{"viewer"}, cache);
       var groupType = com.authx.sdk.ResourceType.of("group",
               com.authx.sdk.model.RelationNamedSubjectTypesTest.LegacyRel.class,
               com.authx.sdk.model.PermissionNamedTest.PermEnum.class);
       a.to(groupType, "eng", "member"); // no throw
   }
   ```

2. **Run:** fails.

3. **Implement:**

   ```java
   /** Typed subject with explicit subject relation: {@code to(Group.TYPE, "eng", "member")}. */
   public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
   GrantResult to(ResourceType<R, P> subjectType, String id, String subjectRelation) {
       return to(new String[]{subjectType.name() + ":" + id + "#" + subjectRelation});
   }
   ```

4. **Run:** passes.
5. **Commit:** `git add -u && git commit -m "feat(sdk): GrantAction.to(ResourceType, id, relation) overload (req-11)"`.

---

## Task T020: `GrantAction.toWildcard(ResourceType)` + `Iterable` typed overload [SR:req-11,req-13]

**Files:**
- Modify: `src/main/java/com/authx/sdk/action/GrantAction.java`
- Modify: `src/test/java/com/authx/sdk/action/GrantActionInferenceTest.java`

**Steps:**

1. **Add tests:**

   ```java
   @Test void wildcardTypedOverload() {
       var cache = new SchemaCache();
       cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
               Set.of("viewer"), Set.of(),
               Map.of("viewer", List.of(SubjectType.wildcard("user"))))));
       var a = new GrantAction("document", "d-1", new InMemoryTransport(),
               new String[]{"viewer"}, cache);
       var userType = com.authx.sdk.ResourceType.of("user",
               com.authx.sdk.model.RelationNamedSubjectTypesTest.LegacyRel.class,
               com.authx.sdk.model.PermissionNamedTest.PermEnum.class);
       a.toWildcard(userType); // writes user:*
   }

   @Test void iterableTypedOverloadWritesN() {
       var cache = new SchemaCache();
       cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
               Set.of("viewer"), Set.of(),
               Map.of("viewer", List.of(SubjectType.of("user"))))));
       var a = new GrantAction("document", "d-1", new InMemoryTransport(),
               new String[]{"viewer"}, cache);
       var userType = com.authx.sdk.ResourceType.of("user",
               com.authx.sdk.model.RelationNamedSubjectTypesTest.LegacyRel.class,
               com.authx.sdk.model.PermissionNamedTest.PermEnum.class);
       var result = a.to(userType, java.util.List.of("a", "b", "c"));
       assertThat(result.count()).isEqualTo(3);
   }
   ```

2. **Run:** fails.

3. **Implement:**

   ```java
   /** {@code user:*} form for wildcard-typed relations. */
   public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
   GrantResult toWildcard(ResourceType<R, P> subjectType) {
       return to(new String[]{subjectType.name() + ":*"});
   }

   /** Batch typed: same subject type, many ids. */
   public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
   GrantResult to(ResourceType<R, P> subjectType, Iterable<String> ids) {
       java.util.List<String> refs = new java.util.ArrayList<>();
       for (String id : ids) refs.add(subjectType.name() + ":" + id);
       return to(refs.toArray(String[]::new));
   }
   ```

4. **Run:** passes.
5. **Commit:** `git add -u && git commit -m "feat(sdk): GrantAction.toWildcard + Iterable typed overload (req-11, req-13)"`.

---

## Task T021: `RevokeAction.from` mirror overloads [SR:req-12,req-13]

**Files:**
- Modify: `src/main/java/com/authx/sdk/action/RevokeAction.java`
- Create: `src/test/java/com/authx/sdk/action/RevokeActionTypedOverloadTest.java`

**Steps:**

1. **Write test** mirroring the Grant side: `from(String id)` inference, `from(ResourceType, id)`, `from(ResourceType, id, relation)`, `fromWildcard(ResourceType)`, `from(ResourceType, Iterable<String>)`. Follow the same test shape as T017-T020.

2. **Implement** — copy the five methods added in T017-T020 into `RevokeAction`, renaming `to*` → `from*` and routing through the existing `from(String...)` path.

3. **Run:** passes.
4. **Commit:** `git add -u && git commit -m "feat(sdk): RevokeAction typed from overloads (req-12, req-13)"`.

---

## Task T022: `CheckAction.by(ResourceType, id)` + `byAll(ResourceType, Iterable<String>)` + `TypedCheckAction` [SR:req-12,req-13]

**Files:**
- Modify: `src/main/java/com/authx/sdk/action/CheckAction.java`
- Modify: `src/main/java/com/authx/sdk/TypedCheckAction.java`
- Create: `src/test/java/com/authx/sdk/action/CheckActionTypedOverloadTest.java`

**Steps:**

1. **Write test:**

   ```java
   package com.authx.sdk.action;

   import com.authx.sdk.ResourceType;
   import com.authx.sdk.transport.InMemoryTransport;
   import org.junit.jupiter.api.Test;
   import static org.assertj.core.api.Assertions.assertThat;

   class CheckActionTypedOverloadTest {

       enum R implements com.authx.sdk.model.Relation.Named {
           VIEWER("viewer"); private final String v; R(String v){this.v=v;}
           @Override public String relationName(){return v;}
       }
       enum P implements com.authx.sdk.model.Permission.Named {
           VIEW("view"); private final String v; P(String v){this.v=v;}
           @Override public String permissionName(){return v;}
       }

       @Test void byTypedBuildsCanonicalRef() {
           var a = new CheckAction("document", "d-1", new InMemoryTransport(),
                   Runnable::run, new String[]{"view"});
           var user = ResourceType.of("user", R.class, P.class);
           // InMemoryTransport returns deny by default — we just want "doesn't throw" + right path.
           a.by(user, "alice");
       }

       @Test void byAllTypedIterable() {
           var a = new CheckAction("document", "d-1", new InMemoryTransport(),
                   Runnable::run, new String[]{"view"});
           var user = ResourceType.of("user", R.class, P.class);
           var m = a.byAll(user, java.util.List.of("a", "b", "c"));
           assertThat(m).isNotNull();
       }
   }
   ```

2. **Run:** fails.

3. **Implement** on `CheckAction`:

   ```java
   import com.authx.sdk.ResourceType;
   import com.authx.sdk.model.Permission;
   import com.authx.sdk.model.Relation;

   public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
   CheckResult by(ResourceType<R, P> subjectType, String id) {
       return by(SubjectRef.of(subjectType.name(), id));
   }

   public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
   BulkCheckResult byAll(ResourceType<R, P> subjectType, Iterable<String> ids) {
       java.util.List<SubjectRef> subjects = new java.util.ArrayList<>();
       for (String id : ids) subjects.add(SubjectRef.of(subjectType.name(), id));
       return byAll(subjects);
   }
   ```

4. **Mirror** on `TypedCheckAction` (check the existing file for `by(...)` entry points and add the typed overloads).

5. **Run:** passes.
6. **Commit:** `git add -u && git add src/test/java/com/authx/sdk/action/CheckActionTypedOverloadTest.java && git commit -m "feat(sdk): Check typed subject overloads (req-12, req-13)"`.

---

## Task T023: `WhoBuilder` + `LookupQuery.findBy(ResourceType, id)` + `TypedFinder` mirror [SR:req-12,req-13]

**Files:**
- Modify: `src/main/java/com/authx/sdk/action/WhoBuilder.java`
- Modify: `src/main/java/com/authx/sdk/LookupQuery.java`
- Modify: `src/main/java/com/authx/sdk/TypedFinder.java`
- Create: `src/test/java/com/authx/sdk/LookupQueryTypedOverloadTest.java`

**Steps:**

1. **Inspect the current signatures** — check which `WhoBuilder` accepts `(String subjectType, Permission.Named)` and which `LookupQuery.findBy(...)` accepts `SubjectRef` vs `(String, String)`. Add typed overloads:
   - `WhoBuilder who(ResourceType<R, P> subjectType, Permission.Named perm)` on whichever class exposes `who(...)`.
   - `LookupQuery.findBy(ResourceType<R, P>, String id)` + `findBy(ResourceType<R, P>, Iterable<String>)`.
   - `TypedFinder` — same pattern.

2. **Write test** asserting the typed overloads produce a canonical `type:id` subject in the underlying request. Use `InMemoryTransport` and inspect the request sent (add a thin recording subclass).

3. **Implement** as in T022.

4. **Run:** passes.
5. **Commit:** `git add -u && git commit -m "feat(sdk): Lookup/who typed subject overloads (req-12, req-13)"`.

---

## Task T024: Matrix of Iterable overloads across all affected actions [P] [SR:req-13]

**Files:**
- Modify (if not covered already): `BatchGrantAction.java`, `BatchRevokeAction.java`, `TypedGrantAction.java`, `TypedRevokeAction.java` (if exists), any action that handles subjects
- Create: `src/test/java/com/authx/sdk/IterableOverloadCoverageTest.java`

**Steps:**

1. **Write coverage test** iterating over the action classes via reflection, asserting each has the `(ResourceType, Iterable<String>)` overload:

   ```java
   package com.authx.sdk;

   import org.junit.jupiter.api.Test;

   import java.lang.reflect.Method;
   import static org.assertj.core.api.Assertions.assertThat;

   class IterableOverloadCoverageTest {

       @Test void grantActionHasIterableTypedTo() {
           assertThat(methodExists(com.authx.sdk.action.GrantAction.class,
                   "to", ResourceType.class, Iterable.class)).isTrue();
       }

       @Test void revokeActionHasIterableTypedFrom() {
           assertThat(methodExists(com.authx.sdk.action.RevokeAction.class,
                   "from", ResourceType.class, Iterable.class)).isTrue();
       }

       @Test void checkActionHasIterableTypedByAll() {
           assertThat(methodExists(com.authx.sdk.action.CheckAction.class,
                   "byAll", ResourceType.class, Iterable.class)).isTrue();
       }

       private boolean methodExists(Class<?> c, String name, Class<?>... paramTypes) {
           for (Method m : c.getMethods()) {
               if (!m.getName().equals(name)) continue;
               if (m.getParameterCount() != paramTypes.length) continue;
               boolean ok = true;
               for (int i = 0; i < paramTypes.length; i++) {
                   if (!paramTypes[i].isAssignableFrom(m.getParameterTypes()[i])) { ok = false; break; }
               }
               if (ok) return true;
           }
           return false;
       }
   }
   ```

2. **Run:** should pass after T020-T023.
3. **Commit:** `git add src/test/java/com/authx/sdk/IterableOverloadCoverageTest.java && git commit -m "test(sdk): coverage for Iterable+ResourceType overloads (req-13)"`.

---

## Task T025: Migrate `DocumentSharingService` [SR:req-14]

**Files:**
- Modify: `test-app/src/main/java/com/authx/testapp/service/DocumentSharingService.java`

**Steps:**

1. **Write failing test** `test-app/src/test/java/com/authx/testapp/service/DocumentSharingServiceApiTest.java` asserting the method bodies no longer do `"user:" + ...` concatenation (via source inspection or a grep-style check):

   ```java
   // File-content regression: reject "user:" + concatenation in the file
   var src = Files.readString(Path.of("src/main/java/com/authx/testapp/service/DocumentSharingService.java"));
   assertThat(src).doesNotContain("\"user:\"");
   assertThat(src).doesNotContain("\"group:\"");
   assertThat(src).doesNotContain("\"folder:\"");
   ```

2. **Run:** fails (current file has those strings).

3. **Rewrite** the methods per spec appendix B:

   ```java
   public void shareWithUser(String docId, String targetUserId, ShareLevel level) {
       client.on(Document.TYPE)
               .select(docId)
               .grant(relFor(level))
               .to(User.TYPE, targetUserId);
   }

   public void shareWithUsers(String docId, List<String> userIds, ShareLevel level) {
       client.on(Document.TYPE)
               .select(docId)
               .grant(relFor(level))
               .to(User.TYPE, userIds);
   }

   public void shareWithGroup(String docId, String groupId, ShareLevel level) {
       client.on(Document.TYPE)
               .select(docId)
               .grant(relFor(level))
               .to(Group.TYPE, groupId, "member");
   }

   public void makePublic(String docId) {
       client.on(Document.TYPE)
               .select(docId)
               .grant(Document.Rel.VIEWER)
               .toWildcard(User.TYPE);
   }

   public void shareTemporarily(String docId, String targetUserId, ShareLevel level, Duration ttl) {
       client.on(Document.TYPE)
               .select(docId)
               .grant(relFor(level))
               .expiringIn(ttl)
               .to(User.TYPE, targetUserId);
   }

   public void unshareWithUser(String docId, String targetUserId) {
       client.on(Document.TYPE).select(docId).revoke(Document.Rel.VIEWER).from(User.TYPE, targetUserId);
       client.on(Document.TYPE).select(docId).revoke(Document.Rel.COMMENTER).from(User.TYPE, targetUserId);
       client.on(Document.TYPE).select(docId).revoke(Document.Rel.EDITOR).from(User.TYPE, targetUserId);
   }

   public void moveIntoFolder(String docId, String folderId) {
       client.on(Document.TYPE)
               .select(docId)
               .grant(Document.Rel.FOLDER)
               .to(folderId); // single-type inference: folder:<folderId>
   }

   public void attachToSpace(String docId, String spaceId) {
       client.on(Document.TYPE)
               .select(docId)
               .grant(Document.Rel.SPACE)
               .to(spaceId); // single-type inference: space:<spaceId>
   }

   public List<String> myReadableDocs(String userId, int max) {
       return client.on(Document.TYPE)
               .findBy(User.TYPE, userId)
               .limit(max)
               .can(Document.Perm.VIEW);
   }

   // Similar for myEditableDocs, myDocsByPermissions, readableDocsForUsers.

   public void unshareWithMany(String docId, List<String> userIds) {
       client.on(Document.TYPE)
               .select(docId)
               .revoke(Document.Rel.VIEWER, Document.Rel.COMMENTER, Document.Rel.EDITOR)
               .from(User.TYPE, userIds);
   }
   ```

4. **Run:** `./gradlew :test-app:compileJava :test-app:test` — all green.
5. **Commit:** `git add -u test-app/ && git commit -m "feat(test-app): migrate DocumentSharingService to typed subject overloads (req-14)"`.

---

### Phase 3 gate

```bash
./gradlew test -x :test-app:test -x :cluster-test:test   # SDK green
./gradlew test                                           # Everything green
```

**Open PR-C:** `feature/pr-c-typed-subject-overloads` → `main`.

---

# Phase 4: PR-D — Typed caveat

After PR-C merges:
```bash
git checkout main && git pull
git checkout -b feature/pr-d-typed-caveat
```

## Task T026: Add `ip_allowlist` caveat to `deploy/schema.zed` [SR:req-15]

**Files:**
- Modify: `deploy/schema.zed`

**Steps:**

1. **Append** to `deploy/schema.zed`:

   ```
   // ============================================================
   //  Caveats
   // ============================================================

   /**
    * 限定来源 IP 段分享 —— 只有请求 IP 在允许 CIDR 列表内时才授予权限。
    */
   caveat ip_allowlist(cidrs list<string>, client_ip string) {
       client_ip.matches(cidrs)
   }
   ```

2. **Commit:** `git add -u deploy/schema.zed && git commit -m "feat(schema): ip_allowlist caveat for typed-caveat demo (req-15)"`.

---

## Task T027: Regenerate test-app schema to emit `IpAllowlist` + `Caveats` [SR:req-15]

**Files:**
- Modify: `src/test/java/com/authx/sdk/RegenerateTestAppSchemaTool.java` (add caveat definition)
- Generate: `test-app/src/main/java/com/authx/testapp/schema/IpAllowlist.java`
- Generate: `test-app/src/main/java/com/authx/testapp/schema/Caveats.java`

**Steps:**

1. **Extend** `RegenerateTestAppSchemaTool` — in addition to `cache.updateFromMap(...)` also call:

   ```java
   cache.updateCaveats(Map.of(
           "ip_allowlist", new SchemaCache.CaveatDef(
                   "ip_allowlist",
                   java.util.LinkedHashMap.newLinkedHashMap(2),  // or a Map.of with ordered keys
                   "client_ip.matches(cidrs)",
                   "限定来源 IP 段分享 — 只有请求 IP 在允许 CIDR 列表内时才授予权限。")));
   ```
   (Populate the LinkedHashMap with `("cidrs", "list<string>")` and `("client_ip", "string")`.)

2. **Run the regeneration** (toggle `@Disabled` off, run, toggle back on) per T009 Step 4.

3. **Verify generated files exist:** `ls test-app/src/main/java/com/authx/testapp/schema/IpAllowlist.java test-app/src/main/java/com/authx/testapp/schema/Caveats.java`.

4. **Verify smoke:** `./gradlew :test-app:compileJava`.

5. **Commit:** `git add test-app/src/main/java/com/authx/testapp/schema/IpAllowlist.java test-app/src/main/java/com/authx/testapp/schema/Caveats.java src/test/java/com/authx/sdk/RegenerateTestAppSchemaTool.java && git commit -m "feat(test-app): generate IpAllowlist + Caveats (req-15)"`.

---

## Task T028: Demo `ConditionalShareService` + test [SR:req-15]

**Files:**
- Create: `test-app/src/main/java/com/authx/testapp/service/ConditionalShareService.java`
- Create: `test-app/src/test/java/com/authx/testapp/service/ConditionalShareServiceTest.java`

**Steps:**

1. **Write failing test** `test-app/src/test/java/com/authx/testapp/service/ConditionalShareServiceTest.java`:

   ```java
   package com.authx.testapp.service;

   import com.authx.sdk.AuthxClient;
   import com.authx.testapp.schema.Document;
   import com.authx.testapp.schema.IpAllowlist;
   import org.junit.jupiter.api.Test;

   import java.util.List;
   import java.util.Map;

   import static org.assertj.core.api.Assertions.assertThat;

   class ConditionalShareServiceTest {

       @Test void refBuildsCaveatRefWithCidrs() {
           var ref = IpAllowlist.ref(IpAllowlist.CIDRS, List.of("10.0.0.0/8"));
           assertThat(ref.name()).isEqualTo("ip_allowlist");
           assertThat(ref.context()).containsEntry("cidrs", List.of("10.0.0.0/8"));
       }

       @Test void contextBuildsMapForCheck() {
           Map<String, Object> ctx = IpAllowlist.context(IpAllowlist.CLIENT_IP, "10.0.0.5");
           assertThat(ctx).containsEntry("client_ip", "10.0.0.5");
       }

       @Test void serviceCallsCompile() {
           var client = AuthxClient.inMemory();
           var svc = new ConditionalShareService(client);
           // These will hit InMemoryTransport — we just verify the fluent chain compiles & runs.
           svc.shareConditional("d-1", "alice", List.of("10.0.0.0/8"));
       }
   }
   ```

2. **Run:** fails (service missing).

3. **Implement** `ConditionalShareService`:

   ```java
   package com.authx.testapp.service;

   import com.authx.sdk.AuthxClient;
   import com.authx.testapp.schema.Document;
   import com.authx.testapp.schema.IpAllowlist;
   import com.authx.testapp.schema.User;

   import java.util.List;

   /**
    * Demo: typed caveat API end-to-end.
    *
    * <p>Grant-time: {@code IpAllowlist.ref(CIDRS, list)} constructs a
    * {@code CaveatRef} whose parameter names match the generated constants.
    * Check-time: {@code IpAllowlist.context(CLIENT_IP, ip)} builds the
    * dynamic context Map for {@code .withContext(...)}.
    */
   public class ConditionalShareService {

       private final AuthxClient client;

       public ConditionalShareService(AuthxClient client) {
           this.client = client;
       }

       public void shareConditional(String docId, String userId, List<String> cidrs) {
           client.on(Document.TYPE)
                   .select(docId)
                   .grant(Document.Rel.VIEWER)
                   .withCaveat(IpAllowlist.ref(IpAllowlist.CIDRS, cidrs))
                   .to(User.TYPE, userId);
       }

       public boolean canOpenFromIp(String userId, String docId, String clientIp) {
           return client.on(Document.TYPE)
                   .select(docId)
                   .check(Document.Perm.VIEW)
                   .given(IpAllowlist.CLIENT_IP, clientIp)
                   .by(User.TYPE, userId);
       }
   }
   ```

4. **Run:** `./gradlew :test-app:test --tests ConditionalShareServiceTest` — passes.
5. **Run:** `./gradlew test` (full suite).
6. **Commit:** `git add test-app/src/main/java/com/authx/testapp/service/ConditionalShareService.java test-app/src/test/java/com/authx/testapp/service/ConditionalShareServiceTest.java && git commit -m "feat(test-app): ConditionalShareService demoing typed caveat (req-15)"`.

---

### Phase 4 gate

```bash
./gradlew test                                           # All green
```

**Open PR-D:** `feature/pr-d-typed-caveat` → `main`.

---

## Dependencies

- **T002, T003** are independent; T003 uses only `SubjectType` which T002 introduces → T003 depends on T002.
- **T004** depends on T002 (uses `SubjectType` in records).
- **T005** depends on T004.
- **T006** depends on T004.
- **T007** depends on T005, T006.
- **T008** depends on T002, T006.
- **T009** depends on T008.
- **T010 → T011** sequential (test → impl).
- **T012** depends on T011 (validation method must exist).
- **T013, T014** depend on T012 (share the plumbing pattern).
- **T015** depends on T012 — smoke test only; can run in parallel with T013/T014.
- **T016 → T017** sequential.
- **T018, T019, T020** depend on T017 (all modify `GrantAction`).
- **T021, T022, T023** are parallelizable with each other once T017 lands; each modifies a different action class.
- **T024** runs after T020-T023 complete.
- **T025** depends on all of T017-T023.
- **T026 → T027 → T028** sequential in Phase 4.

## Parallelizable tasks [P]

- Phase 1: T003 [P with T004]; T005 & T006 [P].
- Phase 2: T013, T014, T015 [P] after T012.
- Phase 3: T021, T022, T023 [P] after T017.
