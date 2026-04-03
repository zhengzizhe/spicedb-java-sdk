# AuthCSES SDK — AI Agent Integration Guide

## Quick Start (copy-paste ready)

```java
// 1. Initialize (one singleton per app)
var client = AuthCsesClient.builder()
    .connection(c -> c.target("localhost:50051").presharedKey("dev-token"))
    .cache(c -> c.enabled(true))
    .build();

// 2. Simple style — most cases
var doc = client.on("document");
doc.grant("doc-1", "editor", "alice");
doc.check("doc-1", "view", "alice");                  // → true

// 3. Chain style — complex cases (caveat, expiry, batch)
doc.resource("doc-1").grant("editor").expiresIn(Duration.ofDays(7)).to("bob");
doc.resource("doc-1").batch()
    .revoke("owner").from("old-owner")
    .grant("owner").to("new-owner")
    .execute();

// 4. Close
client.close();
```

## API Cheat Sheet

### Simple methods (1-line, on ResourceFactory)

| I want to... | Code |
|--------------|------|
| Check permission | `doc.check("doc-1", "view", "alice")` → `boolean` |
| Check with consistency | `doc.check("doc-1", "view", "alice", Consistency.full())` |
| Check returning full result | `doc.checkResult("doc-1", "view", "alice")` → `CheckResult` |
| Async check | `doc.checkAsync("doc-1", "view", "alice")` → `CompletableFuture<Boolean>` |
| Check all permissions at once | `doc.checkAll("doc-1", "alice", "view", "edit", "delete")` → `Map<String,Boolean>` |
| Filter who can access | `doc.filterAllowed("doc-1", "view", userIdList)` → `List<String>` |
| Grant to users | `doc.grant("doc-1", "editor", "alice", "bob")` |
| Grant to users (Collection) | `doc.grant("doc-1", "editor", userIdList)` |
| Grant to department/group | `doc.grantToSubjects("doc-1", "viewer", "department:eng#member")` |
| Grant to everyone | `doc.grantToSubjects("doc-1", "link_viewer", "user:*")` |
| Revoke from users | `doc.revoke("doc-1", "editor", "alice")` |
| Revoke from department | `doc.revokeFromSubjects("doc-1", "viewer", "department:eng#member")` |
| Remove all access | `doc.revokeAll("doc-1", "alice")` |
| Who has permission | `doc.subjects("doc-1", "view")` → `List<String>` |
| Who has permission (Set) | `doc.subjectSet("doc-1", "view")` → `Set<String>` |
| Who has permission (count) | `doc.subjectCount("doc-1", "view")` → `int` |
| Who has permission (exists) | `doc.hasSubjects("doc-1", "view")` → `boolean` |
| Who has relation | `doc.relatedUsers("doc-1", "editor")` → `List<String>` |
| All collaborators grouped | `doc.allRelations("doc-1")` → `Map<String,List<String>>` |
| Collaborator count | `doc.relationCount("doc-1")` → `int` |
| User's accessible resources | `doc.resources("view", "alice")` → `List<String>` |
| User's resources (limited) | `doc.resources("view", "alice", 100)` → `List<String>` |
| User has any access | `doc.hasResources("edit", "alice")` → `boolean` |

### Chain methods (on ResourceHandle, for complex scenarios)

| I want to... | Code |
|--------------|------|
| Grant with expiry | `doc.resource("doc-1").grant("editor").expiresIn(Duration.ofDays(7)).to("alice")` |
| Grant with caveat | `doc.resource("doc-1").grant("viewer").withCaveat("ip_range", ctx).to("alice")` |
| Check with caveat context | `doc.resource("doc-1").check("view").withContext(ctx).by("alice")` |
| Expand permission tree | `doc.resource("doc-1").expand("view")` → `ExpandTree` |
| Atomic batch operations | `doc.resource("doc-1").batch().grant("owner").to("new").revoke("owner").from("old").execute()` |
| Subjects with limit | `doc.resource("doc-1").who().withPermission("view").limit(100).fetch()` |
| Relations raw tuples | `doc.resource("doc-1").relations("editor").fetch()` → `List<Tuple>` |

## Relation vs Permission

**CRITICAL: Do not confuse these.**

| Concept | Used with | Example | What it means |
|---------|-----------|---------|---------------|
| **Relation** | `grant()`, `revoke()`, `relatedUsers()` | "editor", "owner", "viewer" | Direct relationship stored in SpiceDB |
| **Permission** | `check()`, `subjects()`, `resources()` | "view", "edit", "delete" | Computed from relations (includes inheritance) |

```
WRONG: doc.check("doc-1", "editor", "alice")    ← "editor" is a relation!
RIGHT: doc.check("doc-1", "edit", "alice")       ← "edit" is the permission
RIGHT: doc.grant("doc-1", "editor", "alice")     ← grant uses relation
```

## Subject Types

```java
// Default: bare string = "user" type
doc.grant("doc-1", "editor", "alice");                              // → user:alice

// Non-user subjects: use grantToSubjects / revokeFromSubjects
doc.grantToSubjects("doc-1", "viewer", "department:sales#member");  // → entire department
doc.grantToSubjects("doc-1", "editor", "group:eng#member");         // → entire group
doc.grantToSubjects("doc-1", "link_viewer", "user:*");              // → everyone (link sharing)
doc.revokeFromSubjects("doc-1", "link_viewer", "user:*");           // → disable link sharing
```

## Configuration

```java
// Grouped style (recommended for production)
AuthCsesClient.builder()
    .connection(c -> c
        .target("dns:///spicedb.prod:50051")
        .presharedKey("key")
        .tls(true)
        .requestTimeout(Duration.ofSeconds(5)))
    .cache(c -> c
        .enabled(true)
        .maxSize(100_000)
        .watchInvalidation(true))
    .features(f -> f
        .virtualThreads(true)
        .shutdownHook(true)
        .telemetry(true))
    .extend(e -> e
        .components(SdkComponents.builder()
            .l2Cache(myRedisCache)
            .tokenStore(myRedisTokenStore)
            .build()))
    .build();

// Flat style (quick setup)
AuthCsesClient.builder()
    .target("localhost:50051")
    .presharedKey("key")
    .cacheEnabled(true)
    .build();
```

## Testing

```java
var client = AuthCsesClient.inMemory();
var doc = client.on("document");
doc.grant("doc-1", "editor", "alice");
assertTrue(doc.check("doc-1", "editor", "alice")); // InMemory: relation = permission
```

## Spring Boot Integration

```java
@Configuration
public class SdkConfig {
    @Bean(destroyMethod = "close")
    public AuthCsesClient authCsesClient(
            @Value("${spicedb.target}") String target,
            @Value("${spicedb.key}") String key) {
        return AuthCsesClient.builder()
            .connection(c -> c.target(target).presharedKey(key))
            .cache(c -> c.enabled(true))
            .build();
    }
}

@RestController
public class DocController {
    private final ResourceFactory doc;

    public DocController(AuthCsesClient client) {
        this.doc = client.on("document");
    }

    @GetMapping("/docs/{id}/ui")
    public Map<String, Boolean> ui(@PathVariable String id, @RequestHeader("X-User-Id") String uid) {
        return doc.checkAll(id, uid, "view", "edit", "share", "delete");
    }
}
```

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Using relation in `check()` | Use permission: `check(id, "view", uid)` not `check(id, "viewer", uid)` |
| Creating multiple clients | One singleton per app |
| Forgetting to close | Use `.features(f -> f.shutdownHook(true))` or `@Bean(destroyMethod="close")` |
| Not enabling cache | Add `.cache(c -> c.enabled(true))` for production |
| Hardcoding strings | Use codegen: `AuthCsesCodegen.generate(client, outputDir, pkg)` |
| Injecting 3 permission beans | Just inject `AuthCsesClient`, use `client.on("document")` |
