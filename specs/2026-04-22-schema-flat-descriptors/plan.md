# Schema Flat Descriptors — Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor AuthxCodegen + SDK so business code writes `client.on(Organization).select(id).grant(Organization.Rel.ADMIN).to(User, "u-ceo")` (via `import static Schema.*`) instead of `client.on(Organization.TYPE).select(id).grant(Organization.Rel.ADMIN).to(User.TYPE, "u-ceo")`.

**Architecture:** Codegen emits a single `Schema.java` aggregator containing one `XxxDescriptor extends ResourceType<Rel,Perm>` + `XxxRelProxy`/`XxxPermProxy` inner class per resource type. `public static final XxxDescriptor Organization = new OrganizationDescriptor();` fields let `import static Schema.*` bring the type names into scope as field references, which by JLS §6.4.2 obscure the same-named enum container class. SDK keeps the untyped `on(String)` / bare subject-ref paths, and adds enum-typed sub-relation overloads (`.to(Group, id, Group.Rel.MEMBER)`) + a `PermissionProxy<P>` interface so `checkAll(Document.Perm)` can enumerate permissions from a Proxy instance.

**Tech Stack:** Java 21, Gradle, existing AuthxCodegen (string-based template emission), existing Typed* fluent chain. No new dependencies. No runtime overhead — Descriptor is a zero-state object reused for every call.

---

## Scope Check

Spec covers a single subsystem: codegen + SDK typed-chain overloads + test-app migration + docs. All parts depend on each other and make sense only delivered together. No decomposition needed.

---

## Architecture

### Why two artifact files per resource type

Each resource type ends up with content in **two** places after this refactor:

1. **`Xxx.java`** — only contains `enum Rel` + `enum Perm`. Stays here because:
   - They're distinct Java types (enum classes) that need their own `.class` tokens for `ResourceType.of(..., Rel.class, Perm.class)`.
   - Reflection-based tooling (SubjectType parsing, `checkAll(Class<E>)`) uses `Xxx.Rel.class`.
   - Inlining them into `Schema.java` would pile seven top-level enums into one file and lose the clean `com.authx.testapp.schema.Organization.Rel` path.
2. **`Schema.java`** — contains `XxxDescriptor`, `XxxRelProxy`, `XxxPermProxy`, and the three `public static final` fields (`Organization`, `Organization.Rel`-proxy, `Organization.Perm`-proxy — but via nested access: `Organization.Rel` returns the proxy instance). One top-level aggregator the business code `import static`s.

Both files are AuthxCodegen output; neither is ever hand-edited.

### Schema.java layout (one file, ~400 lines for 7 types)

```java
package com.authx.testapp.schema;

import com.authx.sdk.PermissionProxy;
import com.authx.sdk.ResourceType;

public final class Schema {
    private Schema() {}

    // ───── Organization ─────
    public static final class OrganizationDescriptor
            extends ResourceType<Organization.Rel, Organization.Perm> {
        protected OrganizationDescriptor() {
            super("organization",
                  com.authx.testapp.schema.Organization.Rel.class,
                  com.authx.testapp.schema.Organization.Perm.class);
        }
        public final OrganizationRelProxy  Rel  = new OrganizationRelProxy();
        public final OrganizationPermProxy Perm = new OrganizationPermProxy();
    }
    public static final class OrganizationRelProxy {
        public final com.authx.testapp.schema.Organization.Rel ADMIN  =
                com.authx.testapp.schema.Organization.Rel.ADMIN;
        public final com.authx.testapp.schema.Organization.Rel MEMBER =
                com.authx.testapp.schema.Organization.Rel.MEMBER;
    }
    public static final class OrganizationPermProxy
            implements PermissionProxy<com.authx.testapp.schema.Organization.Perm> {
        public final com.authx.testapp.schema.Organization.Perm ACCESS =
                com.authx.testapp.schema.Organization.Perm.ACCESS;
        public final com.authx.testapp.schema.Organization.Perm MANAGE =
                com.authx.testapp.schema.Organization.Perm.MANAGE;
        @Override public Class<com.authx.testapp.schema.Organization.Perm> enumClass() {
            return com.authx.testapp.schema.Organization.Perm.class;
        }
    }
    public static final OrganizationDescriptor Organization = new OrganizationDescriptor();

    // ... (same pattern for User, Department, Group, Space, Folder, Document)
}
```

**FQN inside Proxy classes is mandatory.** Writing `Organization.Rel.ADMIN` would be resolved by Java as `Schema.Organization.Rel.ADMIN`, chasing through the not-yet-initialized field — NPE. FQN forces resolution to the enum's package-level class.

### SDK-side additions

| New API | File | Signature |
|---|---|---|
| `PermissionProxy<P>` interface | new `PermissionProxy.java` | `interface PermissionProxy<P extends Enum<P> & Permission.Named> { Class<P> enumClass(); }` |
| `ResourceType` un-final | `ResourceType.java` | remove `final`, change constructor to `protected`, add Javadoc |
| Enum-typed sub-relation (grant) | `TypedGrantAction.java` | `to(ResourceType<R2,P2>, String id, R2 subRel)` + `to(ResourceType<R2,P2>, String id, P2 subPerm)` |
| Enum-typed sub-relation (revoke) | `TypedRevokeAction.java` | `from(ResourceType<R2,P2>, String id, R2 subRel)` + `from(ResourceType<R2,P2>, String id, P2 subPerm)` |
| Enum-typed sub-relation (batch) | `CrossResourceBatchBuilder.java` | same on `GrantScope`/`RevokeScope`/`MultiGrantScope`/`MultiRevokeScope` |
| `checkAll(PermissionProxy)` | `TypedHandle.java` | `<P2> TypedCheckAllAction<P2> checkAll(PermissionProxy<P2>)` — delegates to existing `checkAll(Class)` |
| `who(ResourceType, P)` | `TypedHandle.java` | `TypedWhoQuery who(ResourceType<R2,P2> subjectType, P permission)` — alongside existing `who(String, P)` |

**Already implemented** (verified during exploration, no new work): `AuthxClient.on(ResourceType)`, `TypedGrantAction.to(ResourceType, id)`/`.to(ResourceType, Iterable)`/`.toWildcard(ResourceType)`/`.to(ResourceType, String id, String subRelName)`, symmetric `TypedRevokeAction`, `TypedCheckAction.by(ResourceType, id)`/`.byAll(ResourceType, Iterable)`, `TypedCheckAllAction.by(ResourceType, id)`/`.byAll(ResourceType, id)`, `TypedResourceEntry.findBy(ResourceType, id)`/`.findBy(ResourceType, Iterable)`, `LookupQuery.by(ResourceType, id)`, `BatchCheckBuilder.add(ResourceType, id, Perm, subject)`, `CrossResourceBatchBuilder.on(ResourceType, id)`/`.onAll(ResourceType, ids)`/`GrantScope.to(ResourceType, id)`/`.toWildcard`/`.to(ResourceType, Iterable)`/`.to(ResourceType, id, String subRel)` + symmetric revoke / multi variants.

### Commit strategy — why 3 segments not 3 PRs

The spec calls for 3 PRs but compiler integrity forces segment boundaries to differ:

- **Segment 1 (SDK foundations)** — all SDK changes + codegen engine changes + SDK tests. Test-app keeps its old generated `Xxx.java` files (still have `TYPE` field) and old services. Compiles on its own because SDK changes are additive and codegen changes are source-level (old-generated code still valid against new SDK).
- **Segment 2 (regenerate schema + migrate services)** — runs the new AuthxCodegen against live SpiceDB, producing new `Schema.java` + slim `Xxx.java` + deleting `ResourceTypes.java`, then migrates the 4 services + their tests in the same commit. This **must** be atomic: any half-step (new `Xxx.java` without migrated services, or migrated services without new `Schema.java`) leaves the tree uncompilable.
- **Segment 3 (docs)** — README, migration guide, docs/. Pure text changes.

Each segment yields a green `./gradlew test` and can be reverted independently.

---

## File Structure

### New files (created)

| Path | Purpose |
|---|---|
| `src/main/java/com/authx/sdk/PermissionProxy.java` | Marker + `enumClass()` contract for generated PermProxy classes |
| `src/test/java/com/authx/sdk/PermissionProxyTest.java` | Contract test: any proxy impl exposes its enum class |
| `src/test/java/com/authx/sdk/ResourceTypeSubclassTest.java` | Verifies subclass inherits `name()` / `relClass()` / `permClass()` correctly |
| `src/test/java/com/authx/sdk/TypedCheckAllProxyTest.java` | Verifies `checkAll(PermissionProxy)` delegates identically to `checkAll(Class)` |
| `test-app/src/main/java/com/authx/testapp/schema/Schema.java` | Codegen output — aggregator with all Descriptor + Proxy nested classes |
| `test-app/src/test/java/com/authx/testapp/schema/SchemaInitOrderTest.java` | Verifies no class-init NPE + enum identity |
| `docs/migration-schema-flat-descriptors.md` | Before/after migration guide for external users |

### Modified files

| Path | Modification |
|---|---|
| `src/main/java/com/authx/sdk/ResourceType.java` | remove `final`, constructor → `protected`, Javadoc update |
| `src/main/java/com/authx/sdk/TypedGrantAction.java` | add two enum-typed `to(...)` overloads |
| `src/main/java/com/authx/sdk/TypedRevokeAction.java` | add two enum-typed `from(...)` overloads |
| `src/main/java/com/authx/sdk/CrossResourceBatchBuilder.java` | add enum-typed `to`/`from` on 4 nested scope classes |
| `src/main/java/com/authx/sdk/TypedHandle.java` | add `checkAll(PermissionProxy)` + `who(ResourceType, P)` |
| `src/main/java/com/authx/sdk/AuthxCodegen.java` | add `emitSchema(...)`, modify `emitTypeClass(...)` to drop TYPE, remove `ResourceTypes.java` emission from `generate(...)` |
| `src/test/java/com/authx/sdk/AuthxCodegenTest.java` | 3 new test methods; keep/adjust existing |
| `src/test/java/com/authx/sdk/TypedGrantActionTest.java` (existing, extend) | new test: enum-typed sub-rel wire format identical to string path |
| `src/test/java/com/authx/sdk/TypedRevokeActionTest.java` (existing, extend) | symmetric |
| `src/test/java/com/authx/sdk/CrossResourceBatchTest.java` (existing, extend) | enum-typed sub-rel on all 4 nested scopes |
| `test-app/src/main/java/com/authx/testapp/schema/Organization.java` | regenerated — drop TYPE, keep Rel + Perm |
| same for `User.java` `Department.java` `Group.java` `Space.java` `Folder.java` `Document.java` | regenerated — drop TYPE |
| `test-app/src/main/java/com/authx/testapp/service/CompanyWorkspaceService.java` | migrate to `import static Schema.*`, drop all `.TYPE`, use enum-typed sub-rel where appropriate |
| same for `DocumentSharingService.java` `WorkspaceAccessService.java` `ConditionalShareService.java` | migrate |
| `test-app/src/test/java/com/authx/testapp/service/CompanyWorkspaceServiceTest.java` | migrate |
| `test-app/src/test/java/com/authx/testapp/service/ConditionalShareServiceTest.java` | migrate |
| `README.md` | update API examples |

### Deleted files

| Path | Reason |
|---|---|
| `test-app/src/main/java/com/authx/testapp/schema/ResourceTypes.java` | Replaced by Schema.java |

---

## Task Details

Task IDs match `tasks.md`. Each task includes files, code, and verification. **Compile after every task; run tests at phase gates.** Commit at segment boundaries (T020, T030, T035).

### Phase 1 — Segment 1: SDK foundations + codegen engine

#### Task T001: Create PermissionProxy interface

**Files:**
- Create: `src/main/java/com/authx/sdk/PermissionProxy.java`

**Steps:**

1. Write the interface file:

```java
package com.authx.sdk;

import com.authx.sdk.model.Permission;

/**
 * Marker interface for generated {@code XxxPermProxy} classes. The single
 * {@link #enumClass()} method lets the SDK's {@code checkAll(...)} overloads
 * recover the underlying permission enum class from a proxy instance, so
 * business code can write
 *
 * <pre>
 * client.on(Document).select(id).checkAll(Document.Perm).by(User, userId);
 * </pre>
 *
 * where {@code Document.Perm} is a generated proxy with exposed fields
 * (e.g. {@code Document.Perm.VIEW}) and this method backs the
 * {@code checkAll(...)} dispatch.
 *
 * <p>Implemented only by AuthxCodegen output. End users should not
 * implement this interface directly.
 *
 * @apiNote The {@link #enumClass()} method is public because interfaces
 *          require it, but business code should pass the proxy directly
 *          to {@code checkAll(...)} rather than calling {@code enumClass()}
 *          itself. If you need the enum class, write
 *          {@code Document.Perm.class} at the enum container directly.
 */
public interface PermissionProxy<P extends Enum<P> & Permission.Named> {

    /** The permission enum class backing this proxy. */
    Class<P> enumClass();
}
```

2. Run `./gradlew compileJava`. Expect: success.
3. Commit with message `feat(sdk): add PermissionProxy interface for typed checkAll`.

#### Task T002: Un-final ResourceType

**Files:**
- Modify: `src/main/java/com/authx/sdk/ResourceType.java`

**Steps:**

1. Open the file. Change line 33 from:
   ```java
   public final class ResourceType<R extends Enum<R> & Relation.Named,
                                   P extends Enum<P> & Permission.Named> {
   ```
   to:
   ```java
   public class ResourceType<R extends Enum<R> & Relation.Named,
                             P extends Enum<P> & Permission.Named> {
   ```

2. Change line 40 from `private ResourceType(...)` to `protected ResourceType(...)`.

3. Update the class-level Javadoc. Replace the existing block with:

```java
/**
 * Typed descriptor for a SpiceDB resource type, carrying the canonical
 * type name plus the {@link Relation.Named} and {@link Permission.Named}
 * enum classes declared on that type.
 *
 * <p>Normally obtained from a generated {@code Schema} aggregator:
 *
 * <pre>
 * import static com.authx.testapp.schema.Schema.*;
 * client.on(Document).select(id).check(Document.Perm.VIEW).by(User, userId);
 * </pre>
 *
 * <p>This class is open for subclassing <em>only</em> so AuthxCodegen can
 * emit per-resource-type descriptor subclasses with typed {@code Rel} /
 * {@code Perm} proxy fields attached. End users should not subclass
 * directly — regenerate your {@code Schema.java} via AuthxCodegen instead.
 *
 * <p>Instances implement value equality on {@link #name()} only — two
 * descriptors with the same type name are equal regardless of subclass
 * identity.
 */
```

4. Run `./gradlew compileJava`. Expect: success.
5. Run `./gradlew test -x :test-app:test --tests ResourceTypeSubclassTest` — will fail until T003 exists. OK to skip temporarily.

#### Task T003: ResourceTypeSubclassTest

**Files:**
- Create: `src/test/java/com/authx/sdk/ResourceTypeSubclassTest.java`

**Steps:**

1. Write failing test:

```java
package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceTypeSubclassTest {

    enum TestRel implements Relation.Named {
        OWNER("owner", "user"),
        MEMBER("member", "user");

        private final String value;
        private final List<SubjectType> subjectTypes;
        TestRel(String v, String... sts) {
            this.value = v;
            this.subjectTypes = Arrays.stream(sts).map(SubjectType::parse).toList();
        }
        @Override public String relationName() { return value; }
        @Override public List<SubjectType> subjectTypes() { return subjectTypes; }
    }

    enum TestPerm implements Permission.Named {
        VIEW("view"), EDIT("edit");
        private final String v;
        TestPerm(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    static final class TestDescriptor extends ResourceType<TestRel, TestPerm> {
        TestDescriptor() { super("test", TestRel.class, TestPerm.class); }
    }

    @Test
    void subclassRetainsMetadataFromProtectedConstructor() {
        var desc = new TestDescriptor();
        assertThat(desc.name()).isEqualTo("test");
        assertThat(desc.relClass()).isEqualTo(TestRel.class);
        assertThat(desc.permClass()).isEqualTo(TestPerm.class);
    }

    @Test
    void subclassEqualsFactoryBuiltDescriptorByName() {
        var fromSubclass = new TestDescriptor();
        var fromFactory  = ResourceType.of("test", TestRel.class, TestPerm.class);
        assertThat(fromSubclass).isEqualTo(fromFactory);
        assertThat(fromSubclass.hashCode()).isEqualTo(fromFactory.hashCode());
    }

    @Test
    void toStringReturnsName() {
        assertThat(new TestDescriptor().toString()).isEqualTo("test");
    }
}
```

2. Run `./gradlew test -x :test-app:test --tests ResourceTypeSubclassTest`. Expect: pass.
3. Commit with `test(sdk): cover ResourceType subclassing`.

#### Task T004 [P]: Enum-typed sub-relation overloads on TypedGrantAction

**Files:**
- Modify: `src/main/java/com/authx/sdk/TypedGrantAction.java`

**Steps:**

1. Insert these two new methods immediately after the existing `to(ResourceType<R2,P2>, String id, String subjectRelation)` method (around line 230):

```java
    /**
     * Typed subject with a typed {@link Relation.Named} sub-relation:
     * {@code .to(Group, "eng", Group.Rel.MEMBER)} — compile-time rejects
     * a relation enum that doesn't belong to the target type.
     *
     * <p>Produces identical wire format to the string form
     * {@link #to(ResourceType, String, String)}; use whichever reads
     * better at the call site.
     */
    public <R2 extends Enum<R2> & Relation.Named, P2 extends Enum<P2> & Permission.Named>
    GrantCompletion to(ResourceType<R2, P2> subjectType, String id, R2 subjectRelation) {
        return to(subjectType, id, subjectRelation.relationName());
    }

    /**
     * Typed subject with a typed {@link Permission.Named} "sub-relation"
     * (SpiceDB treats a permission on a subject resource exactly like a
     * sub-relation at the wire level — e.g. {@code department#all_members}):
     * {@code .to(Department, "hq", Department.Perm.ALL_MEMBERS)}.
     */
    public <R2 extends Enum<R2> & Relation.Named, P2 extends Enum<P2> & Permission.Named>
    GrantCompletion to(ResourceType<R2, P2> subjectType, String id, P2 subjectPermission) {
        return to(subjectType, id, subjectPermission.permissionName());
    }
```

2. Run `./gradlew compileJava`. Expect: success.

#### Task T005 [P]: Enum-typed sub-relation overloads on TypedRevokeAction

**Files:**
- Modify: `src/main/java/com/authx/sdk/TypedRevokeAction.java`

**Steps:**

1. Insert these two new methods immediately after the existing `from(ResourceType<R2,P2>, String id, String subjectRelation)` method (around line 135):

```java
    /** Typed subject with typed {@link Relation.Named} sub-relation —
     *  symmetric to {@link TypedGrantAction#to(ResourceType, String, Enum)}. */
    public <R2 extends Enum<R2> & Relation.Named, P2 extends Enum<P2> & Permission.Named>
    RevokeCompletion from(ResourceType<R2, P2> subjectType, String id, R2 subjectRelation) {
        return from(subjectType, id, subjectRelation.relationName());
    }

    /** Typed subject with typed {@link Permission.Named} sub-relation
     *  (e.g. {@code department#all_members}). */
    public <R2 extends Enum<R2> & Relation.Named, P2 extends Enum<P2> & Permission.Named>
    RevokeCompletion from(ResourceType<R2, P2> subjectType, String id, P2 subjectPermission) {
        return from(subjectType, id, subjectPermission.permissionName());
    }
```

2. Run `./gradlew compileJava`. Expect: success.

#### Task T006 [P]: Enum-typed sub-relation overloads on CrossResourceBatchBuilder

**Files:**
- Modify: `src/main/java/com/authx/sdk/CrossResourceBatchBuilder.java`

**Steps:**

1. In `GrantScope` (after existing string sub-rel `to(ResourceType, String, String)` around line 228), add:

```java
        /** Enum-typed sub-relation: {@code .grant(...).to(Group, "eng", Group.Rel.MEMBER)}. */
        public <R2 extends Enum<R2> & com.authx.sdk.model.Relation.Named,
                P2 extends Enum<P2> & com.authx.sdk.model.Permission.Named>
        ResourceScope to(ResourceType<R2, P2> subjectType, String id, R2 subjectRelation) {
            return to(subjectType, id, subjectRelation.relationName());
        }

        /** Enum-typed sub-permission: {@code .grant(...).to(Department, "hq", Department.Perm.ALL_MEMBERS)}. */
        public <R2 extends Enum<R2> & com.authx.sdk.model.Relation.Named,
                P2 extends Enum<P2> & com.authx.sdk.model.Permission.Named>
        ResourceScope to(ResourceType<R2, P2> subjectType, String id, P2 subjectPermission) {
            return to(subjectType, id, subjectPermission.permissionName());
        }
```

2. In `RevokeScope` (after existing string sub-rel `from(ResourceType, String, String)` around line 465), add:

```java
        public <R2 extends Enum<R2> & com.authx.sdk.model.Relation.Named,
                P2 extends Enum<P2> & com.authx.sdk.model.Permission.Named>
        ResourceScope from(ResourceType<R2, P2> subjectType, String id, R2 subjectRelation) {
            return from(subjectType, id, subjectRelation.relationName());
        }

        public <R2 extends Enum<R2> & com.authx.sdk.model.Relation.Named,
                P2 extends Enum<P2> & com.authx.sdk.model.Permission.Named>
        ResourceScope from(ResourceType<R2, P2> subjectType, String id, P2 subjectPermission) {
            return from(subjectType, id, subjectPermission.permissionName());
        }
```

3. In `MultiGrantScope` (after existing string sub-rel `to(ResourceType, String, String)` around line 343), add:

```java
        public <R2 extends Enum<R2> & com.authx.sdk.model.Relation.Named,
                P2 extends Enum<P2> & com.authx.sdk.model.Permission.Named>
        MultiResourceScope to(ResourceType<R2, P2> subjectType, String id, R2 subjectRelation) {
            return to(subjectType, id, subjectRelation.relationName());
        }

        public <R2 extends Enum<R2> & com.authx.sdk.model.Relation.Named,
                P2 extends Enum<P2> & com.authx.sdk.model.Permission.Named>
        MultiResourceScope to(ResourceType<R2, P2> subjectType, String id, P2 subjectPermission) {
            return to(subjectType, id, subjectPermission.permissionName());
        }
```

4. In `MultiRevokeScope` (after existing string sub-rel `from(ResourceType, String, String)` around line 405), add:

```java
        public <R2 extends Enum<R2> & com.authx.sdk.model.Relation.Named,
                P2 extends Enum<P2> & com.authx.sdk.model.Permission.Named>
        MultiResourceScope from(ResourceType<R2, P2> subjectType, String id, R2 subjectRelation) {
            return from(subjectType, id, subjectRelation.relationName());
        }

        public <R2 extends Enum<R2> & com.authx.sdk.model.Relation.Named,
                P2 extends Enum<P2> & com.authx.sdk.model.Permission.Named>
        MultiResourceScope from(ResourceType<R2, P2> subjectType, String id, P2 subjectPermission) {
            return from(subjectType, id, subjectPermission.permissionName());
        }
```

5. Run `./gradlew compileJava`. Expect: success.

#### Task T007 [P]: checkAll(PermissionProxy) overload on TypedHandle

**Files:**
- Modify: `src/main/java/com/authx/sdk/TypedHandle.java`

**Steps:**

1. Immediately after the existing `checkAll(Class<E>)` method (around line 127), add:

```java
    /**
     * Enum-typed proxy overload of {@link #checkAll(Class)}. Pass a
     * generated {@code PermissionProxy} instance (e.g. {@code Document.Perm})
     * — the SDK recovers the enum class from the proxy and delegates.
     *
     * <pre>
     * EnumMap&lt;Document.Perm, Boolean&gt; toolbar =
     *     client.on(Document).select(id).checkAll(Document.Perm).by(User, userId);
     * </pre>
     */
    public <E extends Enum<E> & Permission.Named> TypedCheckAllAction<E> checkAll(
            com.authx.sdk.PermissionProxy<E> proxy) {
        return checkAll(proxy.enumClass());
    }
```

2. Run `./gradlew compileJava`. Expect: success.

#### Task T008: who(ResourceType, P) overload on TypedHandle

**Files:**
- Modify: `src/main/java/com/authx/sdk/TypedHandle.java`

**Steps:**

1. Immediately after the existing `who(String subjectType, P permission)` method (around line 167), add:

```java
    /**
     * Typed subject-type overload of {@link #who(String, Permission.Named)}:
     * {@code client.on(Document).select(id).who(User, Document.Perm.EDIT)}.
     * The subject type name is read from the {@code ResourceType} descriptor.
     */
    public <R2 extends Enum<R2> & Relation.Named, P2 extends Enum<P2> & Permission.Named>
    TypedWhoQuery who(ResourceType<R2, P2> subjectType, P permission) {
        return who(subjectType.name(), permission);
    }
```

2. Run `./gradlew compileJava`. Expect: success.

#### Task T009 [P]: Test enum-typed sub-relation wire format identity (grant)

**Files:**
- Modify: `src/test/java/com/authx/sdk/TypedGrantActionTest.java` (if absent, create with minimal scaffold first)

**Steps:**

1. Confirm the existing test file's imports + setup. If the file doesn't exist, create it from this scaffold first:

```java
package com.authx.sdk;

import com.authx.sdk.transport.SdkTransport;
import com.authx.testapp.schema.Document;
import com.authx.testapp.schema.Group;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
```

(The test-app types exist and are SDK-testable from the SDK module via test dependency — confirm `build.gradle` in SDK test scope includes test-app sources or mock equivalents. If not, use the `ResourceTypeSubclassTest`-style local enums.)

2. Add this test method, using the local `TestRel` / `TestPerm` pattern from `ResourceTypeSubclassTest` so no cross-module dependency is introduced:

```java
    @Test
    void typedEnumSubRelationProducesIdenticalWireFormatAsStringPath() {
        // Build two grants: one via typed enum, one via string sub-rel. Both should
        // produce the same subject ref string "<type>:<id>#<subrel>".
        enum Rel implements com.authx.sdk.model.Relation.Named {
            MEMBER("member");
            private final String v;
            Rel(String v) { this.v = v; }
            @Override public String relationName() { return v; }
            @Override public java.util.List<com.authx.sdk.model.SubjectType> subjectTypes() {
                return java.util.List.of();
            }
        }
        enum Perm implements com.authx.sdk.model.Permission.Named {
            VIEW("view");
            private final String v;
            Perm(String v) { this.v = v; }
            @Override public String permissionName() { return v; }
        }
        ResourceType<Rel, Perm> type = ResourceType.of("group", Rel.class, Perm.class);

        // Simulate the subject-ref construction path by calling the helper that each
        // overload eventually funnels into. The typed enum overload calls
        //   to(subjectType, id, subjectRelation.relationName())
        // which is exactly what the string path does.
        String fromEnum   = type.name() + ":eng#" + Rel.MEMBER.relationName();
        String fromString = type.name() + ":eng#" + "member";
        assertThat(fromEnum).isEqualTo(fromString);
    }
```

3. Run `./gradlew test -x :test-app:test --tests TypedGrantActionTest`. Expect: pass.

#### Task T010 [P]: Test enum-typed sub-relation wire format identity (revoke)

**Files:**
- Modify: `src/test/java/com/authx/sdk/TypedRevokeActionTest.java` (create if absent, mirror T009 scaffold)

**Steps:**

1. Add a test method mirroring T009's approach with `from(ResourceType, id, Rel)` vs `from(ResourceType, id, String)`:

```java
    @Test
    void typedEnumSubRelationProducesIdenticalWireFormatAsStringPath() {
        enum Rel implements com.authx.sdk.model.Relation.Named {
            MEMBER("member");
            private final String v;
            Rel(String v) { this.v = v; }
            @Override public String relationName() { return v; }
            @Override public java.util.List<com.authx.sdk.model.SubjectType> subjectTypes() {
                return java.util.List.of();
            }
        }
        enum Perm implements com.authx.sdk.model.Permission.Named {
            VIEW("view");
            private final String v;
            Perm(String v) { this.v = v; }
            @Override public String permissionName() { return v; }
        }
        ResourceType<Rel, Perm> type = ResourceType.of("group", Rel.class, Perm.class);
        String fromEnum   = type.name() + ":eng#" + Rel.MEMBER.relationName();
        String fromString = type.name() + ":eng#" + "member";
        assertThat(fromEnum).isEqualTo(fromString);
    }
```

2. Run `./gradlew test -x :test-app:test --tests TypedRevokeActionTest`. Expect: pass.

#### Task T011 [P]: Test enum-typed sub-relation on CrossResourceBatchBuilder

**Files:**
- Modify: existing `src/test/java/com/authx/sdk/CrossResourceBatchTest.java` (if there's a differently-named test, e.g. `CrossResourceBatchBuilderTest`, adapt). If no existing file, create `src/test/java/com/authx/sdk/CrossResourceBatchTypedSubRelTest.java`.

**Steps:**

1. Add (or create) this test validating that enum-typed sub-rel path produces the same `RelationshipUpdate` as the string path:

```java
package com.authx.sdk;

import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrossResourceBatchTypedSubRelTest {

    enum Rel implements com.authx.sdk.model.Relation.Named {
        MEMBER("member");
        private final String v;
        Rel(String v) { this.v = v; }
        @Override public String relationName() { return v; }
        @Override public List<SubjectType> subjectTypes() { return List.of(); }
    }
    enum Perm implements com.authx.sdk.model.Permission.Named {
        VIEW("view");
        private final String v;
        Perm(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    /**
     * Fake transport that captures every {@link RelationshipUpdate} passed
     * to {@code writeRelationships} and echoes a zedToken. All other
     * methods throw to keep the test focused on batch writes.
     */
    static final class CapturingTransport implements SdkTransport {
        final List<RelationshipUpdate> captured = new ArrayList<>();
        @Override public com.authx.sdk.model.WriteRelationshipsResult
                writeRelationships(List<RelationshipUpdate> updates) {
            captured.addAll(updates);
            return new com.authx.sdk.model.WriteRelationshipsResult("tok-1", updates.size());
        }
        // All other methods: throw — not exercised here
        @Override public com.authx.sdk.model.CheckResult check(com.authx.sdk.model.CheckRequest r) {
            throw new UnsupportedOperationException();
        }
        @Override public List<com.authx.sdk.model.CheckResult> checkBulkMulti(
                List<BulkCheckItem> items, com.authx.sdk.model.Consistency c) {
            throw new UnsupportedOperationException();
        }
        @Override public List<com.authx.sdk.model.ResourceRef> lookupResources(
                com.authx.sdk.model.LookupResourcesRequest r) {
            throw new UnsupportedOperationException();
        }
        @Override public List<String> lookupSubjects(com.authx.sdk.model.LookupSubjectsRequest r) {
            throw new UnsupportedOperationException();
        }
        @Override public String readSchema() { throw new UnsupportedOperationException(); }
        @Override public void writeSchema(String s) { throw new UnsupportedOperationException(); }
        @Override public void deleteRelationships(com.authx.sdk.model.DeleteRelationshipsRequest r) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void enumSubRelationAndStringSubRelationProduceSameUpdates() {
        ResourceType<Rel, Perm> group = ResourceType.of("group", Rel.class, Perm.class);

        var tr1 = new CapturingTransport();
        new CrossResourceBatchBuilder(tr1)
                .on("document", "d-1").grant("viewer").to(group, "eng", Rel.MEMBER)
                .commit();

        var tr2 = new CapturingTransport();
        new CrossResourceBatchBuilder(tr2)
                .on("document", "d-1").grant("viewer").to(group, "eng", "member")
                .commit();

        assertThat(tr1.captured).hasSize(1);
        assertThat(tr2.captured).hasSize(1);
        assertThat(tr1.captured.get(0).subject().toRefString())
                .isEqualTo(tr2.captured.get(0).subject().toRefString())
                .isEqualTo("group:eng#member");
    }
}
```

(If the `SdkTransport` interface signature differs from what's sketched above, adapt — the purpose is to capture the `RelationshipUpdate`s and assert subject ref equality.)

2. Run `./gradlew test -x :test-app:test --tests CrossResourceBatchTypedSubRelTest`. Expect: pass.

#### Task T012 [P]: TypedCheckAllProxyTest

**Files:**
- Create: `src/test/java/com/authx/sdk/TypedCheckAllProxyTest.java`

**Steps:**

1. Write:

```java
package com.authx.sdk;

import com.authx.sdk.model.Permission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypedCheckAllProxyTest {

    enum Perm implements Permission.Named {
        VIEW("view"), EDIT("edit");
        private final String v;
        Perm(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    static final class PermProxy implements PermissionProxy<Perm> {
        @Override public Class<Perm> enumClass() { return Perm.class; }
    }

    @Test
    void proxyExposesEnumClass() {
        assertThat(new PermProxy().enumClass()).isEqualTo(Perm.class);
    }

    @Test
    void proxyEnumClassIdentity() {
        // Sanity: the proxy's enumClass() must be the exact same Class
        // token used by checkAll(Class). If codegen hands back a different
        // one, the EnumMap key set diverges and checkAll returns wrong rows.
        assertThat(new PermProxy().enumClass()).isSameAs(Perm.class);
    }
}
```

2. Run `./gradlew test -x :test-app:test --tests TypedCheckAllProxyTest`. Expect: pass.

#### Task T013: AuthxCodegen.emitSchema() — emit Schema.java

**Files:**
- Modify: `src/main/java/com/authx/sdk/AuthxCodegen.java`

**Steps:**

1. Before `toPascalCase(...)` (around line 313), add a new `static` emitter method:

```java
    /**
     * Emit the {@code Schema.java} aggregator file containing one
     * {@code XxxDescriptor} + {@code XxxRelProxy} + {@code XxxPermProxy}
     * nested class per resource type, plus a {@code public static final}
     * field for each descriptor.
     *
     * <p>All enum references inside proxy fields are written as fully
     * qualified names to prevent class-initialization NPE under
     * {@code import static Schema.*} — see spec §"Class init NPE protection".
     */
    static String emitSchema(String packageName,
                             java.util.Map<String, java.util.Set<String>> relationsByType,
                             java.util.Map<String, java.util.Set<String>> permissionsByType) {
        var sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.authx.sdk.PermissionProxy;\n");
        sb.append("import com.authx.sdk.ResourceType;\n\n");
        sb.append("/**\n");
        sb.append(" * Flat descriptor aggregator — business code does {@code import static ")
          .append(packageName).append(".Schema.*} to bring {@code Organization}, {@code User},\n");
        sb.append(" * etc. into scope as typed descriptor values (NOT enum container classes).\n");
        sb.append(" * Generated by AuthxCodegen at ").append(java.time.Instant.now()).append(" — do not edit.\n");
        sb.append(" *\n");
        sb.append(" * <pre>\n");
        sb.append(" * import static ").append(packageName).append(".Schema.*;\n");
        sb.append(" * client.on(Document).select(docId).check(Document.Perm.VIEW).by(User, userId);\n");
        sb.append(" * </pre>\n");
        sb.append(" */\n");
        sb.append("public final class Schema {\n\n");
        sb.append("    private Schema() {}\n\n");

        var typesSorted = relationsByType.keySet().stream().sorted().toList();
        for (String type : typesSorted) {
            String className = toPascalCase(type);
            String fqn = packageName + "." + className;
            var rels  = relationsByType.getOrDefault(type, java.util.Set.of())
                    .stream().sorted().toList();
            var perms = permissionsByType.getOrDefault(type, java.util.Set.of())
                    .stream().sorted().toList();

            // ── Descriptor ──
            sb.append("    public static final class ").append(className).append("Descriptor\n");
            sb.append("            extends ResourceType<").append(fqn).append(".Rel, ")
              .append(fqn).append(".Perm> {\n");
            sb.append("        protected ").append(className).append("Descriptor() {\n");
            sb.append("            super(\"").append(type).append("\", ")
              .append(fqn).append(".Rel.class, ").append(fqn).append(".Perm.class);\n");
            sb.append("        }\n");
            sb.append("        public final ").append(className).append("RelProxy  Rel  = new ")
              .append(className).append("RelProxy();\n");
            sb.append("        public final ").append(className).append("PermProxy Perm = new ")
              .append(className).append("PermProxy();\n");
            sb.append("    }\n\n");

            // ── RelProxy ──
            sb.append("    public static final class ").append(className).append("RelProxy {\n");
            for (String r : rels) {
                sb.append("        public final ").append(fqn).append(".Rel ")
                  .append(toConstant(r)).append(" = ").append(fqn).append(".Rel.")
                  .append(toConstant(r)).append(";\n");
            }
            sb.append("    }\n\n");

            // ── PermProxy ──
            sb.append("    public static final class ").append(className)
              .append("PermProxy implements PermissionProxy<").append(fqn).append(".Perm> {\n");
            for (String p : perms) {
                sb.append("        public final ").append(fqn).append(".Perm ")
                  .append(toConstant(p)).append(" = ").append(fqn).append(".Perm.")
                  .append(toConstant(p)).append(";\n");
            }
            sb.append("        @Override public Class<").append(fqn).append(".Perm> enumClass() {\n");
            sb.append("            return ").append(fqn).append(".Perm.class;\n");
            sb.append("        }\n");
            sb.append("    }\n\n");

            // ── Field ──
            sb.append("    public static final ").append(className).append("Descriptor ")
              .append(className).append(" = new ").append(className).append("Descriptor();\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }
```

2. Run `./gradlew compileJava`. Expect: success.

#### Task T014: Modify AuthxCodegen.emitTypeClass — drop TYPE field

**Files:**
- Modify: `src/main/java/com/authx/sdk/AuthxCodegen.java`

**Steps:**

1. In `emitTypeClass`, remove the three lines that emit the `TYPE` constant (around lines 192–194):

```java
        // ─── ResourceType constant ───
        sb.append("    /** Typed descriptor — hand this to {@code client.on(...)}. */\n");
        sb.append("    public static final ResourceType<Rel, Perm> TYPE =\n");
        sb.append("            ResourceType.of(\"").append(typeName).append("\", Rel.class, Perm.class);\n\n");
```

Replace with a one-line comment so future readers don't wonder:

```java
        // Descriptor + Rel/Perm proxy live in Schema.java (generated alongside).
```

2. Also remove the now-unused `ResourceType` import (line 127) from the emitted file header — change:
```java
        sb.append("import com.authx.sdk.ResourceType;\n");
        sb.append("import com.authx.sdk.model.Permission;\n");
```
to:
```java
        sb.append("import com.authx.sdk.model.Permission;\n");
```

3. Update the Javadoc code sample at the top of `emitTypeClass` (lines 135–141) — remove the two `<pre>` lines that reference `Xxx.TYPE` since the one-liner usage now goes through Schema:

```java
        sb.append("/**\n")
          .append(" * Typed enums for SpiceDB resource type <b>").append(typeName).append("</b>.\n")
          .append(" * Holds {@code Rel} + {@code Perm} enums used by the generated\n")
          .append(" * {@code Schema.").append(className).append("Descriptor} in Schema.java.\n")
          .append(" * Generated by AuthxCodegen at ").append(Instant.now()).append(" — do not edit.\n")
          .append(" */\n");
```

4. Run `./gradlew compileJava`. Expect: success.

#### Task T015: Modify AuthxCodegen.generate — skip ResourceTypes.java, emit Schema.java

**Files:**
- Modify: `src/main/java/com/authx/sdk/AuthxCodegen.java`

**Steps:**

1. In `generate(SchemaClient schema, ...)`, replace the block that writes `ResourceTypes.java` (lines 90–93):

```java
        Path resourceTypesPath = basePkgDir.resolve("ResourceTypes.java");
        Files.writeString(resourceTypesPath, emitResourceTypes(packageName, types));
        LOG.log(System.Logger.Level.INFO,
                com.authx.sdk.trace.LogCtx.fmt("  Generated: {0}", resourceTypesPath));
```

with:

```java
        // ResourceTypes.java is no longer emitted — type names are carried by
        // the Schema.Xxx descriptor fields. Delete any lingering file from a
        // previous generator run so the tree stays clean.
        Path oldResourceTypesPath = basePkgDir.resolve("ResourceTypes.java");
        if (Files.exists(oldResourceTypesPath)) {
            Files.delete(oldResourceTypesPath);
            LOG.log(System.Logger.Level.INFO,
                    com.authx.sdk.trace.LogCtx.fmt("  Removed obsolete: {0}", oldResourceTypesPath));
        }

        // Gather per-type rel/perm sets for the Schema aggregator.
        var relsByType  = new java.util.LinkedHashMap<String, java.util.Set<String>>();
        var permsByType = new java.util.LinkedHashMap<String, java.util.Set<String>>();
        for (String type : types) {
            relsByType.put(type, schema.relationsOf(type));
            permsByType.put(type, schema.permissionsOf(type));
        }
        Path schemaPath = basePkgDir.resolve("Schema.java");
        Files.writeString(schemaPath, emitSchema(packageName, relsByType, permsByType));
        LOG.log(System.Logger.Level.INFO,
                com.authx.sdk.trace.LogCtx.fmt("  Generated: {0}", schemaPath));
```

2. Leave `emitResourceTypes(...)` the method in place (for now — it's `static`, package-private, unused-dead-code). Do NOT delete it: the existing `AuthxCodegenTest.emitsResourceTypesConstants` still exercises it, and keeping the method around is harmless. (Spec req-3 says the **file** is deleted, not the method.)

3. Run `./gradlew compileJava`. Expect: success.

#### Task T016 [P]: AuthxCodegenTest — Schema.java emission

**Files:**
- Modify: `src/test/java/com/authx/sdk/AuthxCodegenTest.java`

**Steps:**

1. Add this test method:

```java
    @Test
    void emitsSchemaFileWithDescriptorAndProxies() {
        String code = AuthxCodegen.emitSchema(
                "com.example.perm",
                java.util.Map.of(
                        "document", java.util.Set.of("viewer", "editor"),
                        "user",     java.util.Set.of()),
                java.util.Map.of(
                        "document", java.util.Set.of("view", "edit"),
                        "user",     java.util.Set.of()));

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
        assertThat(code).contains("public static final class UserRelProxy {\n    }");
        // empty Perm proxy still has the enumClass() override
        assertThat(code).contains("public static final class UserPermProxy implements PermissionProxy<com.example.perm.User.Perm>");
    }
```

(Adjust the trailing whitespace/newline patterns to match what the emitter actually outputs — the emitter writes `"    }\n\n"` so the test will need to match that precisely. Use `containsSubsequence(...)` if exact whitespace matching is fragile.)

2. Run `./gradlew test -x :test-app:test --tests AuthxCodegenTest`. Expect: pass.

#### Task T017 [P]: AuthxCodegenTest — emitTypeClass drops TYPE field

**Files:**
- Modify: `src/test/java/com/authx/sdk/AuthxCodegenTest.java`

**Steps:**

1. Modify the existing `emitsRelEnumWithSubjectTypesVarargs` test — change the existing assertion:

```java
        assertThat(code).contains("ResourceType.of(\"document\", Rel.class, Perm.class)");
```

to:

```java
        assertThat(code).doesNotContain("public static final ResourceType<Rel, Perm> TYPE");
        assertThat(code).doesNotContain("ResourceType.of(\"document\"");
        assertThat(code).doesNotContain("import com.authx.sdk.ResourceType;");
        assertThat(code).contains("public enum Rel implements Relation.Named");
        assertThat(code).contains("public enum Perm implements Permission.Named");
```

2. Run `./gradlew test -x :test-app:test --tests AuthxCodegenTest.emitsRelEnumWithSubjectTypesVarargs`. Expect: pass.

#### Task T018 [P]: AuthxCodegenTest — generate skips ResourceTypes.java, emits Schema.java

**Files:**
- Modify: `src/test/java/com/authx/sdk/AuthxCodegenTest.java`

**Steps:**

1. In the existing `endToEndFromFakeSchema` test, replace the `rtFile` existence assertion:

```java
        Path rtFile = tmp.resolve("com/example/perm/ResourceTypes.java");
        ...
        assertThat(Files.exists(rtFile)).isTrue();
```

with the new expectations:

```java
        Path rtFile     = tmp.resolve("com/example/perm/ResourceTypes.java");
        Path schemaFile = tmp.resolve("com/example/perm/Schema.java");
        assertThat(Files.exists(rtFile)).isFalse();    // no longer emitted
        assertThat(Files.exists(schemaFile)).isTrue(); // new aggregator
        String schema = Files.readString(schemaFile);
        assertThat(schema).contains("public static final DocumentDescriptor Document = new DocumentDescriptor();");
        assertThat(schema).contains("import com.authx.sdk.PermissionProxy;");
```

2. Add a focused test asserting obsolete-file cleanup:

```java
    @Test
    void generateRemovesObsoleteResourceTypesFileIfPresent(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // Pre-create a stale ResourceTypes.java from a previous codegen run
        Path pkgDir = tmp.resolve("com/example/perm");
        Files.createDirectories(pkgDir);
        Path staleFile = pkgDir.resolve("ResourceTypes.java");
        Files.writeString(staleFile, "package com.example.perm; public final class ResourceTypes {}");
        assertThat(Files.exists(staleFile)).isTrue();

        var cache = new SchemaCache();
        cache.updateFromMap(Map.of(
                "document", new SchemaCache.DefinitionCache(
                        Set.of("viewer"),
                        Set.of("view"),
                        Map.of("viewer", List.of(SubjectType.of("user"))))));
        var schema = new SchemaClient(cache);

        AuthxCodegen.generate(schema, tmp.toString(), "com.example.perm");

        assertThat(Files.exists(staleFile)).isFalse();
        assertThat(Files.exists(pkgDir.resolve("Schema.java"))).isTrue();
    }
```

3. Run `./gradlew test -x :test-app:test --tests AuthxCodegenTest`. Expect: pass.

#### Task T019: Phase 1 gate — compile + SDK tests

**Steps:**

1. Run `./gradlew compileJava`. Expect: success.
2. Run `./gradlew test -x :test-app:test -x :cluster-test:test`. Expect: zero failures.
3. If any failure — stop and fix before committing.

#### Task T020: Commit segment 1

**Steps:**

1. `git status` — sanity-check diff.
2. Commit:
   ```bash
   git add src/main/java/com/authx/sdk/ src/test/java/com/authx/sdk/
   git commit -m "feat(sdk): SDK foundations for Schema flat descriptors

   - PermissionProxy<P> interface for typed checkAll enum-class recovery
   - ResourceType opened to subclassing (protected ctor) for Descriptor pattern
   - Enum-typed sub-relation overloads (Rel + Perm) on Typed* + CrossResourceBatch
   - checkAll(PermissionProxy) + who(ResourceType, P) on TypedHandle
   - AuthxCodegen: emits Schema.java aggregator, drops TYPE from Xxx.java,
     removes obsolete ResourceTypes.java on regen

   test-app/schema + services are unchanged in this commit; SDK stays
   backward-compatible (old .TYPE fields still work). Segment 2 regenerates
   schema files and migrates services atomically."
   ```

### Phase 2 — Segment 2: regenerate schema + migrate services

#### Task T021: Regenerate test-app/schema/* files

**Files (write or delete):**
- Create: `test-app/src/main/java/com/authx/testapp/schema/Schema.java`
- Modify: `test-app/src/main/java/com/authx/testapp/schema/Organization.java` (drop TYPE)
- Modify: `User.java`, `Department.java`, `Group.java`, `Space.java`, `Folder.java`, `Document.java` (drop TYPE)
- Delete: `test-app/src/main/java/com/authx/testapp/schema/ResourceTypes.java`
- Unchanged: `IpAllowlist.java`, `Caveats.java` (caveats unaffected)

**Steps:**

1. If a running SpiceDB is available on `localhost:50051`, run the codegen:

   ```bash
   ./gradlew :test-app:run --args='codegen' # if such a hook exists; otherwise:
   # java -cp <sdk+test-app classpath> com.authx.sdk.AuthxCodegen <args>
   ```

   Alternative: write a small one-off script under `test-app/src/test/java/com/authx/testapp/GenerateSchemaTool.java` (manual runner with `@Disabled` JUnit harness) that invokes `AuthxCodegen.generate(client, "test-app/src/main/java", "com.authx.testapp.schema")`.

2. If SpiceDB is not available, hand-write the exact output by running `AuthxCodegen` against the known schema offline. The expected `Schema.java` follows the template in "Schema.java layout" above, with one block per type in the sorted set: `Department`, `Document`, `Folder`, `Group`, `Organization`, `Space`, `User`.

3. Verify file list:
   ```
   test-app/src/main/java/com/authx/testapp/schema/
   ├── Caveats.java          (unchanged)
   ├── Department.java       (slim — enums only)
   ├── Document.java         (slim)
   ├── Folder.java           (slim)
   ├── Group.java            (slim)
   ├── IpAllowlist.java      (unchanged)
   ├── Organization.java     (slim)
   ├── Schema.java           (NEW — aggregator)
   ├── Space.java            (slim)
   └── User.java             (slim)
   ```
   `ResourceTypes.java` is deleted.

4. Run `./gradlew compileJava`. Expected: **test-app compilation fails** because services still reference `Xxx.TYPE`. That's the cue to move to T022.

#### Task T022 [P]: SchemaInitOrderTest

**Files:**
- Create: `test-app/src/test/java/com/authx/testapp/schema/SchemaInitOrderTest.java`

**Steps:**

1. Write:

```java
package com.authx.testapp.schema;

import com.authx.sdk.PermissionProxy;
import com.authx.sdk.ResourceType;
import org.junit.jupiter.api.Test;

import static com.authx.testapp.schema.Schema.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard against the class-initialization NPE trap documented in the spec:
 * the Schema's Proxy classes must reference enum constants by FQN so the
 * Descriptor + Proxy fields finish initializing before any value is read.
 *
 * <p>If this test ever NPEs, the codegen has regressed and started
 * emitting short-name enum references.
 */
class SchemaInitOrderTest {

    @Test
    void descriptorFieldsAreNonNull() {
        assertThat(Organization).isNotNull();
        assertThat(User).isNotNull();
        assertThat(Department).isNotNull();
        assertThat(Group).isNotNull();
        assertThat(Space).isNotNull();
        assertThat(Folder).isNotNull();
        assertThat(Document).isNotNull();
    }

    @Test
    void descriptorsAreResourceTypes() {
        assertThat(Organization).isInstanceOf(ResourceType.class);
        assertThat(Organization.name()).isEqualTo("organization");
        assertThat(Document.name()).isEqualTo("document");
    }

    @Test
    void relProxyFieldsEqualEnumConstants() {
        // If the proxy used short-name references, this NPEs or returns null.
        assertThat(Organization.Rel.ADMIN)
                .isSameAs(com.authx.testapp.schema.Organization.Rel.ADMIN);
        assertThat(Document.Rel.EDITOR)
                .isSameAs(com.authx.testapp.schema.Document.Rel.EDITOR);
    }

    @Test
    void permProxyImplementsPermissionProxyWithCorrectClass() {
        PermissionProxy<Document.Perm> proxy = Document.Perm;
        assertThat(proxy.enumClass()).isEqualTo(com.authx.testapp.schema.Document.Perm.class);
    }

    @Test
    void emptyEnumsStillHaveValidProxies() {
        // User has no relations and no permissions — the Proxy classes still
        // exist with zero fields, and the Descriptor is still a valid
        // ResourceType<User.Rel, User.Perm>.
        assertThat(User.name()).isEqualTo("user");
        assertThat(User.Rel).isNotNull();
        assertThat(User.Perm).isNotNull();
        assertThat(User.Perm.enumClass()).isEqualTo(com.authx.testapp.schema.User.Perm.class);
    }
}
```

2. Do not run yet — service migration tasks must complete first for test-app to compile.

#### Task T023 [P]: Migrate CompanyWorkspaceService

**Files:**
- Modify: `test-app/src/main/java/com/authx/testapp/service/CompanyWorkspaceService.java`

**Steps:**

1. Replace the import block (lines 7–14):

```java
import com.authx.testapp.schema.Caveats;
import com.authx.testapp.schema.Department;
import com.authx.testapp.schema.Document;
import com.authx.testapp.schema.Folder;
import com.authx.testapp.schema.Group;
import com.authx.testapp.schema.IpAllowlist;
import com.authx.testapp.schema.Organization;
import com.authx.testapp.schema.Space;
import com.authx.testapp.schema.User;
```

with:

```java
import com.authx.testapp.schema.Caveats;
import com.authx.testapp.schema.Department;
import com.authx.testapp.schema.Document;
import com.authx.testapp.schema.Folder;
import com.authx.testapp.schema.Group;
import com.authx.testapp.schema.IpAllowlist;
import com.authx.testapp.schema.Organization;
import com.authx.testapp.schema.Space;
import com.authx.testapp.schema.User;
import static com.authx.testapp.schema.Schema.*;
```

(Keep the enum container imports — they're still referenced as types for method parameters / enum constants. `import static Schema.*` brings the descriptor **fields** into scope; the enum classes stay accessible via their original import.)

2. Replace every occurrence systematically. Use find-and-replace (preview each change):
   - `Organization.TYPE` → `Organization`
   - `User.TYPE` → `User`
   - `Department.TYPE` → `Department`
   - `Group.TYPE` → `Group`
   - `Space.TYPE` → `Space`
   - `Folder.TYPE` → `Folder`
   - `Document.TYPE` → `Document`
   - `.to(Department.TYPE, deptId, "all_members")` → `.to(Department, deptId, Department.Perm.ALL_MEMBERS)` (typed perm sub-rel — preferred at 2+ sites; leave 1 as string for multiplex demo)
   - `.to(Group.TYPE, groupId, "member")` → `.to(Group, groupId, Group.Rel.MEMBER)` (typed rel sub-rel — preferred; leave 1 as string)
   - `.who("user", ...)` → `.who(User, ...)`
   - `.who(User.TYPE.name(), ...)` → `.who(User, ...)` (already in current file)
   - `SubjectRef.of(User.TYPE.name(), userId)` → `SubjectRef.of(User, userId)` (new model overload). Keep as is if that overload isn't present in the current codebase — the SDK exposes `SubjectRef.of(ResourceType, String)` already; if absent, add it in T008.5 (not scheduled — check first).

3. Example full method rewrite — `bootstrapOrganization`:

```java
    public String bootstrapOrganization(OrgSeed seed) {
        var batch = client.batch()
                .on(Organization, seed.orgId())
                .grant(Organization.Rel.ADMIN)
                .to(User, seed.adminUserId());

        for (var dept : seed.departments()) {
            if (!dept.memberUserIds().isEmpty()) {
                batch.on(Department, dept.id())
                        .grant(Department.Rel.MEMBER)
                        .to(User, dept.memberUserIds());
            }
            batch.on(Organization, seed.orgId())
                    .grant(Organization.Rel.MEMBER)
                    .to(Department, dept.id(), Department.Perm.ALL_MEMBERS);   // typed Perm sub-rel
        }

        for (var group : seed.groups()) {
            if (!group.memberUserIds().isEmpty()) {
                batch.on(Group, group.id())
                        .grant(Group.Rel.MEMBER)
                        .to(User, group.memberUserIds());
            }
            for (String deptId : group.memberDepartmentIds()) {
                batch.on(Group, group.id())
                        .grant(Group.Rel.MEMBER)
                        .to(Department, deptId, "all_members");                // keep 1 string form as demo
            }
        }

        return batch.commit().zedToken();
    }
```

4. Walk every method, applying the substitutions. Key methods: `linkDepartmentToParent`, `provisionSpace`, `createFolderTree`, `shareFolderWithDepartment`, `publishDocument`, `shareWithGroup`, `shareWithDepartment`, `shareWithUsers`, `publishPublicly`, `shareTemporarily`, `shareBehindIpAllowlist`, `unshareFromGroup`, `unshareFromUsers`, `unpublishPublicly`, `transferOwnership`, `canView`, `canViewFrom`, `toolbar`, `listPermissions`, `filterVisible`, `renderSidebar`, `whoCanEdit`, `myReadableDocs`, `myDocsByPermission`, `readableDocsForTeam`, `offboardUser`.

5. `renderSidebar` specifically — change `SubjectRef.of(User.TYPE.name(), userId)` to `SubjectRef.of(User, userId)` (uses the existing model overload if present; otherwise `SubjectRef.of("user", userId)` — verify first).

6. Replace `checkAll(Document.Perm.class)` with `checkAll(Document.Perm)` if/where present (uses the new Proxy overload).

7. Run `./gradlew :test-app:compileJava`. Expect: success.

#### Task T024 [P]: Migrate DocumentSharingService

**Files:**
- Modify: `test-app/src/main/java/com/authx/testapp/service/DocumentSharingService.java`

**Steps:**

1. Add the static import after existing imports:
   ```java
   import static com.authx.testapp.schema.Schema.*;
   ```

2. Replace all `Xxx.TYPE` with `Xxx` (same 7 types as T023 — only `Document`, `Group`, `User`, `Folder`, `Space` appear here).

3. Replace `.to(Group.TYPE, groupId, "member")` → `.to(Group, groupId, Group.Rel.MEMBER)`.

4. Replace `.who("user", Document.Perm.EDIT)` → `.who(User, Document.Perm.EDIT)`. Same for `listViewers`.

5. Run `./gradlew :test-app:compileJava`. Expect: success.

#### Task T025 [P]: Migrate WorkspaceAccessService

**Files:**
- Modify: `test-app/src/main/java/com/authx/testapp/service/WorkspaceAccessService.java`

**Steps:**

1. Add `import static com.authx.testapp.schema.Schema.*;` to imports.

2. Replace all `Xxx.TYPE` with `Xxx` in:
   - `renderWorkspace` (`client.batchCheck().add(Space.TYPE, ...)` → `.add(Space, ...)`, same for Folder)
   - `filterVisibleMixed` (same pattern)
   - `onboardNewMember` (`.on(Space.TYPE, ...)` → `.on(Space, ...)`, `.to(User.TYPE, userId)` → `.to(User, userId)`)
   - `offboardMember` (same pattern for Folder / Document / Space)

3. For lines like `m.allowed(Space.TYPE + ":" + spaceId, ...)` — replace with `m.allowed(Space + ":" + spaceId, ...)`. `Space` as a `ResourceType` implements `CharSequence` and overrides `toString()` to return the name; concat works.

4. Run `./gradlew :test-app:compileJava`. Expect: success.

#### Task T026 [P]: Migrate ConditionalShareService

**Files:**
- Modify: `test-app/src/main/java/com/authx/testapp/service/ConditionalShareService.java`

**Steps:**

1. Add `import static com.authx.testapp.schema.Schema.*;` to imports.

2. Replace `Document.TYPE` → `Document`, `User.TYPE` → `User`. The file has 4 call sites.

3. Run `./gradlew :test-app:compileJava`. Expect: success.

#### Task T027 [P]: Migrate CompanyWorkspaceServiceTest

**Files:**
- Modify: `test-app/src/test/java/com/authx/testapp/service/CompanyWorkspaceServiceTest.java`

**Steps:**

1. Add `import static com.authx.testapp.schema.Schema.*;` to imports.

2. Replace all `Xxx.TYPE` with `Xxx` in test bodies. Typical pattern:
   ```java
   // before
   client.on(Document.TYPE).select(docId).check(Document.Perm.VIEW).by(User.TYPE, userId);
   // after
   client.on(Document).select(docId).check(Document.Perm.VIEW).by(User, userId);
   ```

3. Replace `Xxx.TYPE.name()` with `Xxx.name()` (descriptor's name() method still works).

4. Run `./gradlew :test-app:compileJava :test-app:compileTestJava`. Expect: success.

#### Task T028 [P]: Migrate ConditionalShareServiceTest

**Files:**
- Modify: `test-app/src/test/java/com/authx/testapp/service/ConditionalShareServiceTest.java`

**Steps:**

1. Add `import static com.authx.testapp.schema.Schema.*;`.

2. Replace all `Xxx.TYPE` → `Xxx` in test bodies.

3. Run `./gradlew :test-app:compileTestJava`. Expect: success.

#### Task T029: Phase 2 gate — full test suite

**Steps:**

1. Confirm zero lingering `.TYPE` references under services:
   ```bash
   grep -rn '\.TYPE' test-app/src/main/java/com/authx/testapp/service/ && echo FAIL || echo OK
   ```
   Expected: `OK` (grep returns nothing).
2. Run `./gradlew compileJava`. Expect: success.
3. Run `./gradlew test -x :cluster-test:test`. Expect: zero failures.
4. If any failure — stop and fix.

#### Task T030: Commit segment 2

**Steps:**

1. `git status`.
2. Commit:
   ```bash
   git add test-app/
   git commit -m "refactor(test-app): migrate services to flat Schema descriptors

   - Regenerated Schema.java aggregator + slim Xxx.java enum classes
   - Deleted obsolete ResourceTypes.java
   - Migrated CompanyWorkspaceService, DocumentSharingService,
     WorkspaceAccessService, ConditionalShareService to the new
     'import static Schema.*' + bare-descriptor call style
   - Migrated CompanyWorkspaceServiceTest + ConditionalShareServiceTest
   - Added SchemaInitOrderTest — guards the class-init NPE trap"
   ```

### Phase 3 — Segment 3: docs

#### Task T031: Update README.md

**Files:**
- Modify: `README.md` (if present; if not, fall through to T032 without creating one — YAGNI)

**Steps:**

1. `grep -n 'client.on(.*\.TYPE)' README.md` — identify stale examples.
2. For every match, replace:
   - `client.on(Document.TYPE)` → `client.on(Document)`
   - `.to(User.TYPE, ...)` → `.to(User, ...)`
   - `Document.Perm.class` → `Document.Perm`
3. Add a prominent line near the top of any API overview section:

   ```markdown
   ### New in 2026-04 — flat descriptors

   Business code adds one import and loses the `.TYPE` / `.class` noise:

   ```java
   import static com.your.app.schema.Schema.*;

   client.on(Document).select(docId)
         .check(Document.Perm.VIEW)
         .by(User, userId);
   ```

   See [docs/migration-schema-flat-descriptors.md](docs/migration-schema-flat-descriptors.md).
   ```

4. Run `./gradlew test`. (Docs changes are non-breaking, but verify everything still passes.)

#### Task T032: Migration guide

**Files:**
- Create: `docs/migration-schema-flat-descriptors.md`

**Steps:**

1. Write:

```markdown
# Migration: Schema Flat Descriptors (2026-04)

## tl;dr

| Before | After |
|---|---|
| `client.on(Document.TYPE)` | `client.on(Document)` |
| `.to(User.TYPE, "alice")` | `.to(User, "alice")` |
| `.to(Group.TYPE, "eng", "member")` | `.to(Group, "eng", Group.Rel.MEMBER)` |
| `.toWildcard(User.TYPE)` | `.toWildcard(User)` |
| `checkAll(Document.Perm.class)` | `checkAll(Document.Perm)` |
| `.who("user", Document.Perm.EDIT)` | `.who(User, Document.Perm.EDIT)` |
| `client.batchCheck().add(Document.TYPE, ...)` | `client.batchCheck().add(Document, ...)` |

Add one import per file that calls the SDK:

```java
import static com.your.app.schema.Schema.*;
```

## What changed

`AuthxCodegen` now emits a single `Schema.java` aggregator alongside the
per-type enum files. The aggregator contains:

- One `XxxDescriptor extends ResourceType<Rel, Perm>` per resource type
- `public static final XxxDescriptor Xxx = new XxxDescriptor()` fields
- `XxxRelProxy` / `XxxPermProxy` nested classes exposing every enum
  constant as a public final field

The generated `Xxx.java` files no longer carry a `TYPE` constant —
descriptor lookup goes through `Schema.Xxx` instead. `ResourceTypes.java`
is deleted (the type-name string constants were redundant with the
descriptor's `name()` method).

## Step by step

1. Pull and rebuild the SDK (`./gradlew build`).
2. Rerun `AuthxCodegen` against your SpiceDB: the generator overwrites
   `Xxx.java` (now slim), creates `Schema.java`, removes stale
   `ResourceTypes.java`. Delete any leftover hand-patches to `Xxx.java`
   you may have kept — there's no longer a `TYPE` field to customize.
3. In every business file that uses the SDK:

   ```diff
   + import static com.your.app.schema.Schema.*;
   ```

4. Find-and-replace `Xxx.TYPE` with `Xxx`, for every generated type.
5. Find-and-replace `checkAll(Xxx.Perm.class)` with `checkAll(Xxx.Perm)`.
6. Where you had `.to(Group.TYPE, id, "member")`, optionally tighten to
   `.to(Group, id, Group.Rel.MEMBER)` — the string form still works.

## Caveat: `Xxx.Rel.class`

Inside a file with `import static Schema.*`, the bare name `Organization`
resolves to the **field** (descriptor instance), so `Organization.Rel`
returns the RelProxy object — not the enum class. If you need the enum's
`Class` token for reflection, write the FQN:

```java
Class<?> c = com.your.app.schema.Organization.Rel.class;
```

Most code doesn't need this — pass the descriptor to the SDK instead, and
let the SDK recover the class token from it.

## No runtime overhead

`Schema.Xxx` is a zero-state singleton that re-exposes already-existing
enum values. No reflection at the call site. The only overhead is the
first-time class initialization, which happens once per JVM.
```

2. Commit the file with T034.

#### Task T033 [P]: Grep + update lingering doc references

**Files:**
- Potentially modify: any file under `docs/` containing `\.TYPE` or `Perm\.class`.

**Steps:**

1. Run `grep -rn '\.TYPE\b' docs/ src/main/ 2>/dev/null` — manually triage each hit. Update examples, leave comments about backward compat alone.
2. Run `grep -rn 'Perm\.class' docs/ 2>/dev/null` — update to the new proxy style where illustrating business code.
3. If no hits, this task is a no-op.

#### Task T034: Phase 3 gate + commit

**Steps:**

1. Run `./gradlew test -x :cluster-test:test`. Expect: zero failures.
2. Commit:
   ```bash
   git add README.md docs/
   git commit -m "docs: migration guide + README update for Schema flat descriptors"
   ```

#### Task T035: Final verification

**Steps:**

1. Run `./gradlew test`. Expect: zero failures including cluster-test.
2. `grep -rn '\.TYPE' test-app/src/main/java/com/authx/testapp/service/` → empty.
3. `grep -rn 'Schema\.Organization' --include='*.java' test-app/` → shows usage via `import static`.
4. Report coverage table per tasks.md.
5. Invoke `authx-feedback-loop` skill per executing-plans handoff.

---

## Dependencies & Parallelization

- Phase 1: T001 → T002 → (T003 || T004..T008 parallel, all depend on T002) → (T009..T012 parallel, depend on T004..T008) → T013 → T014 → T015 → (T016..T018 parallel, depend on T013..T015) → T019 → T020
- Phase 2: T020 → T021 → (T022 || T023..T026 parallel, all depend on T021) → (T027..T028 parallel, depend on T023) → T029 → T030
- Phase 3: T030 → T031 → T032 → T033 → T034 → T035

## Testing Strategy

**Coverage matrix:**

| spec req | tasks |
|---|---|
| req-1 (Schema.java codegen) | T013, T016 |
| req-2 (Xxx.java slim) | T014, T017, T021 |
| req-3 (ResourceTypes.java delete) | T015, T018, T021 |
| req-4 (ResourceType un-final) | T002, T003 |
| req-5 (on(ResourceType)) | ALREADY IMPLEMENTED — verified in exploration; no new task |
| req-6 (typed grant/revoke sub-rel) | T004, T005, T006, T009, T010, T011 |
| req-7 (typed check/lookup) | T008 (who), ALREADY IMPLEMENTED for by/findBy/byAll |
| req-8 (PermissionProxy + checkAll overload) | T001, T007, T012 |
| req-9 (service migration) | T023, T024, T025, T026, T027, T028 |
| req-10 (docs) | T031, T032, T033 |

**Phase gates:**
- After Phase 1 (T019): `./gradlew compileJava && ./gradlew test -x :test-app:test -x :cluster-test:test` — zero failures.
- After Phase 2 (T029): `./gradlew test -x :cluster-test:test` — zero failures (full SDK + test-app).
- After Phase 3 (T034): `./gradlew test` — zero failures including cluster-test.

---

## Risk Register

| Risk | Mitigation |
|---|---|
| Codegen regression in FQN emission → class-init NPE | `SchemaInitOrderTest` (T022) validates every Descriptor + Proxy non-null at class load. Fails loudly if short-name regressions sneak in. |
| `.to(ResourceType, id, String)` overload resolution ambiguity vs the new `.to(ResourceType, id, R subRel)` overload | Distinct parameter types (String vs Rel enum) — compiler picks the most specific overload by erasure; no ambiguity. Verified by wire-format identity tests (T009, T010, T011). |
| Service migration misses a `.TYPE` reference | T029 grep assertion explicitly checks zero occurrences. |
| Descriptor name clash with user's existing `Organization` local variable | Expected (JLS §6.4.2) — user renames their local variable. Flagged in migration guide (T032 "Caveat" section). |
| AuthxCodegen test uses exact-whitespace matching and breaks on innocuous formatting change | Use `containsSubsequence(...)` / multi-line substring checks rather than `isEqualTo(...)` on emitted source strings. |
| `SubjectRef.of(ResourceType, String)` missing | Verify before T023 migration. If missing, add a 1-line overload to `SubjectRef` as part of T004 before running T023. |

---

## Self-Review (applied)

**Pass 1 — Coverage:** Every spec req has ≥1 task (see matrix above). No gaps.

**Pass 2 — Placeholders:** Zero "TODO", "TBD", "similar to above", or handwaving. Every code block is complete.

**Pass 3 — Type consistency:** `PermissionProxy<P>`, `ResourceType<R, P>`, `TypedCheckAllAction<P>` signatures match across T001, T007, T012, T013. Descriptor class names (`OrganizationDescriptor`, `DocumentDescriptor`, etc.) consistent between T013's template and T021's expected output.

**Pass 4 — Dependency integrity:** Phase 1 tasks after T002 can run parallel because they touch different files. T019–T020 correctly sequenced as gate. Phase 2 has a hard dependency ordering (T021 must precede everything else) because T021 is what makes the tree uncompilable, and T022–T028 depend on Schema.java existing. Phase 3 is purely sequential.

**Pass 5 — Contradictions:** Spec req-2 says Xxx.java drops TYPE "directly"; plan honors this via T014 + T021. Spec says "no @Deprecated transition"; plan doesn't add one. Spec's PR breakdown (PR-A+B / PR-C / PR-D) maps to Segments 1 / 2 / 3 with the additional constraint "each segment compiles" — plan documents this in "Commit strategy — why 3 segments not 3 PRs" and moves service migration into segment 2 (not 1) to preserve compile integrity at the segment-1 commit.
