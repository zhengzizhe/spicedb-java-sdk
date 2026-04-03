# AuthCSES SDK — AI Agent Integration Guide

## Quick Start (copy-paste ready)

```java
// 1. Add dependency
// implementation("com.authcses:authcses-sdk:1.0.0")

// 2. Initialize (one singleton per app)
var client = AuthCsesClient.builder()
    .target("localhost:50051")
    .presharedKey("dev-token")
    .build();

// 3. Use
client.resource("document", "doc-1").grant("editor").to("alice");
client.resource("document", "doc-1").check("view").by("alice").hasPermission(); // true

// 4. Close
client.close();
```

## API Cheat Sheet

| I want to... | Code |
|--------------|------|
| Grant permission | `resource(type, id).grant(relation).to(userId)` |
| Revoke permission | `resource(type, id).revoke(relation).from(userId)` |
| Check permission | `resource(type, id).check(permission).by(userId).hasPermission()` |
| Check multiple permissions | `resource(type, id).checkAll("view","edit","delete").by(userId).toMap()` |
| Who has permission | `resource(type, id).who().withPermission(p).fetch()` |
| Who has relation | `resource(type, id).who().withRelation(r).fetchSet()` |
| List collaborators | `resource(type, id).relations().fetchGroupByRelationSubjectIds()` |
| Find user's resources | `lookup(type).withPermission(p).by(userId).fetch()` |
| Atomic batch | `resource(type, id).batch().grant(r).to(u).revoke(r).from(u).execute()` |
| Cross-resource batch | `client.batch().on(res1).grant(r).to(u).on(res2).revoke(r).from(u).execute()` |
| Health check | `client.health().isHealthy()` |
| Metrics | `client.metrics().snapshot()` |

## Relation vs Permission

**CRITICAL: Do not confuse these.**

| Concept | Used with | Example | What it means |
|---------|-----------|---------|---------------|
| **Relation** | `grant()`, `revoke()`, `relations()` | "editor", "owner", "viewer" | Direct relationship on the resource |
| **Permission** | `check()`, `who().withPermission()`, `lookup()` | "view", "edit", "delete" | Computed from relations (may include inheritance) |

```
WRONG: resource("doc","1").check("editor").by("alice")  ← "editor" is a relation!
RIGHT: resource("doc","1").check("edit").by("alice")     ← "edit" is the permission
RIGHT: resource("doc","1").grant("editor").to("alice")   ← grant uses relation
```

## Subject Types

```java
// Default: bare string = "user" type
.to("alice")                                    // → user:alice

// Non-user subjects
.toSubjects("group:engineering#member")         // → group:engineering#member
.toSubjects("department:sales#member")          // → department:sales#member
```

## Configuration Pattern

```java
// Connection (required)
.target("localhost:50051")              // single address
.targets("h1:50051", "h2:50051")       // multiple addresses
.target("dns:///spicedb.svc:50051")    // K8s DNS
.presharedKey("key")                   // SpiceDB preshared key

// Cache (optional, recommended)
.cacheEnabled(true)
.cacheMaxSize(100_000)
.watchInvalidation(true)               // real-time cross-instance invalidation

// Per-resource-type policy (optional)
// Implement ResourceStrategy interface, one class per type
.register(new DocumentStrategy())
.register(new GroupStrategy())
```

## Testing

```java
// Unit test: zero external deps
var client = AuthCsesClient.inMemory();
resource("doc","1").grant("editor").to("alice");
assertTrue(resource("doc","1").check("editor").by("alice").hasPermission());
// NOTE: InMemory matches relation name = permission name (no recursive computation)
```

## Spring Boot Integration

```java
@Bean(destroyMethod = "close")
public AuthCsesClient authCsesClient(
        @Value("${spicedb.target}") String target,
        @Value("${spicedb.key}") String key) {
    return AuthCsesClient.builder()
        .target(target)
        .presharedKey(key)
        .cacheEnabled(true)
        .build();
}
```

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Using relation in `check()` | Use permission: `check("view")` not `check("viewer")` |
| Creating multiple clients | One singleton per app |
| Forgetting to close | Use `registerShutdownHook(true)` or `@Bean(destroyMethod="close")` |
| Not enabling cache | Add `.cacheEnabled(true)` for production |
| Hardcoding strings | Use codegen: `AuthCsesCodegen.generate(client, outputDir, pkg)` |
