# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

The main SDK does not own an application database, ORM, SQL schema, or
migration system. It talks directly to SpiceDB over gRPC and models SpiceDB
relationship tuples in Java. The optional `sdk-redisson` module stores session
zedTokens in Redis through the `DistributedTokenStore` SPI; it is not an ORM
layer and does not define migrations.

Real examples:

- `src/main/java/com/authx/sdk/transport/GrpcTransport.java` writes and reads
  SpiceDB relationships through `WriteRelationships`, `DeleteRelationships`,
  `ReadRelationships`, `LookupSubjects`, `LookupResources`, and `Expand`.
- `src/main/java/com/authx/sdk/model/Tuple.java` models relationships as
  `resourceType:resourceId#relation@subjectType:subjectId[#subjectRelation]`.
- `src/main/java/com/authx/sdk/transport/InMemoryTransport.java` is an
  in-memory test transport backed by `ConcurrentHashMap`, not a production
  persistence layer.
- `sdk-redisson/src/main/java/com/authx/sdk/redisson/RedissonTokenStore.java`
  stores zedTokens as Redis strings with TTL and swallows Redis failures per
  the SPI contract.

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
- Redis token keys in `RedissonTokenStore` are `keyPrefix + key`; callers pass
  the prefix, for example `authx:token:`.

---

## Common Mistakes

- Do not add ORM or migration guidance to SDK code. There is no Hibernate,
  JPA, JDBC, Flyway, Liquibase, or application-owned SQL database here.
- Do not make `InMemoryTransport` authoritative for SpiceDB semantics. Its
  comments explicitly say it has no permission recursion or schema validation.
- Do not let Redis failures escape from `DistributedTokenStore`
  implementations. `RedissonTokenStore` logs at `WARNING`, returns `null` on
  `get` failure, and does not throw from `set`.
- Do not bypass the existing gRPC conversion helpers when adding SpiceDB
  operations. Keep conversion and `StatusRuntimeException` mapping in the
  transport layer.
