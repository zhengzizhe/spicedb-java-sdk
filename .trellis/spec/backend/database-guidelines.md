# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

The main SDK does not own an application database, ORM, SQL schema, or
migration system. It talks directly to SpiceDB over gRPC and models SpiceDB
relationship tuples in Java. Cross-instance SESSION consistency is exposed as
the `DistributedTokenStore` SPI only; concrete zedToken storage is user-owned
infrastructure, not a module provided by this SDK.

Real examples:

- `src/main/java/com/authx/sdk/transport/GrpcTransport.java` writes and reads
  SpiceDB relationships through `WriteRelationships`, `DeleteRelationships`,
  `ReadRelationships`, `LookupSubjects`, `LookupResources`, and `Expand`.
- `src/main/java/com/authx/sdk/model/Tuple.java` models relationships as
  `resourceType:resourceId#relation@subjectType:subjectId[#subjectRelation]`.
- `src/main/java/com/authx/sdk/transport/InMemoryTransport.java` is an
  in-memory test transport backed by `ConcurrentHashMap`, not a production
  persistence layer.
- `src/main/java/com/authx/sdk/spi/DistributedTokenStore.java` defines the
  best-effort zedToken sharing contract for multi-instance SESSION consistency.

---

## Query Patterns

- Use SDK model records at API boundaries and convert to gRPC only inside
  `GrpcTransport`. For example, `GrpcTransport.check(...)` builds a
  `CheckPermissionRequest` from `CheckRequest`, `ResourceRef`, `Permission`,
  `SubjectRef`, and `Consistency`.
- Keep stream handling close to the gRPC call and close streaming iterators.
  Existing methods such as `readRelationships`, `lookupSubjects`, and
  `lookupResources` use `try (var iterator = CloseableGrpcIterator.from(...))`.
- Preserve compatibility with older SpiceDB servers when the code already does
  so. `lookupSubjects` intentionally does not set `optionalConcreteLimit`;
  it applies the limit Java-side because older SpiceDB versions reject that
  field.
- Batch SpiceDB checks and writes through the existing model/transport APIs
  instead of issuing one-off calls. `GrpcTransport.checkBulkMulti(...)` slices
  large batches by `MAX_BATCH_SIZE`, and `WriteFlow.commit()` sends accumulated
  typed writes as one `WriteRelationships` RPC.
- For tests that do not need SpiceDB, use `AuthxClient.inMemory()` or
  `InMemoryTransport`. That test transport intentionally matches direct
  relation names only and does not compute schema recursion.

## Scenario: User-Owned Distributed Token Store

### 1. Scope / Trigger

- Trigger: multi-instance SESSION consistency needs zedTokens shared across
  JVMs, but the SDK no longer provides a concrete token-store module.

### 2. Signatures

- SPI: `com.authx.sdk.spi.DistributedTokenStore`
- Methods: `void set(String key, String token)` and
  `@Nullable String get(String key)`
- Injection: `SdkComponents.builder().tokenStore(store).build()`

### 3. Contracts

- The SDK owns only the SPI and the `TokenTracker` call sites.
- Applications own the backing storage, client lifecycle, credentials,
  key prefixing, TTL, monitoring, and failure logging.
- `tokenStore == null` means SESSION consistency is limited to one JVM.

### 4. Validation & Error Matrix

- `set` storage failure -> implementation logs/degrades; it must not throw.
- `get` miss or storage failure -> return `null`.
- No configured store in multi-instance deployments -> SDK logs a startup
  warning; callers must choose a weaker consistency mode or provide storage.

### 5. Good/Base/Bad Cases

- Good: user implementation prefixes keys such as `authx:token:`, stores with
  a short TTL such as 60 seconds, and swallows storage failures per contract.
- Base: no `tokenStore`; SESSION works only inside the local JVM.
- Bad: adding a Redis/database client dependency or concrete token-store
  implementation to the main SDK.

### 6. Tests Required

- Keep `TokenTrackerTest` covering local-only behavior, distributed set/get,
  and degradation when the store fails.
- If SDK wiring changes, assert the no-store warning and that
  `SdkComponents.tokenStore()` is passed into `TokenTracker`.

### 7. Wrong vs Correct

#### Wrong

```java
implementation("some.redis:client")
```

in the main SDK to ship a concrete token store.

#### Correct

```java
DistributedTokenStore store = userOwnedStore;
AuthxClient.builder()
        .extend(e -> e.components(SdkComponents.builder().tokenStore(store).build()));
```

---

## Migrations

There are no SQL migrations in this repository. Schema concerns are SpiceDB
schema concerns:

- Runtime schema reflection is loaded through `SchemaLoader` and exposed via
  `SchemaClient`; `AuthxClientBuilder.loadSchemaOnStart(boolean)` controls
  whether reflection is attempted at build time.
- Tests that need a real SpiceDB schema use e2e helpers under
  `src/test/java/com/authx/sdk/e2e`, such as `SpiceDbTestSchema.java`.
- Code generation lives behind `AuthxCodegen` and related tests, not a
  database migration workflow.

---

## Naming Conventions

- Relationship data follows SpiceDB naming, not table/column naming:
  resource type, resource id, relation/permission, subject type, subject id,
  and optional subject relation.
- Subject refs are canonical `type:id` or `type:id#relation`. `SubjectRef` and
  tests in `ValueObjectTest` document parsing and formatting.
- Validation patterns in `ValidationInterceptor` show current accepted names:
  resource types and permissions/relations are lowercase snake-case style
  (`[a-z][a-z0-9_]{0,127}`); resource IDs allow letters, digits, slash,
  underscore, pipe, and hyphen up to 1024 characters.
- User-provided `DistributedTokenStore` implementations should namespace token
  keys so SDK zedTokens do not collide with application data, for example with
  an `authx:token:` prefix.

---

## Common Mistakes

- Do not add ORM or migration guidance to SDK code. There is no Hibernate,
  JPA, JDBC, Flyway, Liquibase, or application-owned SQL database here.
- Do not make `InMemoryTransport` authoritative for SpiceDB semantics. Its
  comments explicitly say it has no permission recursion or schema validation.
- Do not let shared-storage failures escape from `DistributedTokenStore`
  implementations. The SPI contract requires `set` to be best-effort and
  `get` to return `null` on miss or error.
- Do not bypass the existing gRPC conversion helpers when adding SpiceDB
  operations. Keep conversion and `StatusRuntimeException` mapping in the
  transport layer.
