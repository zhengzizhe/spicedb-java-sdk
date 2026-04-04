# SDK Architecture Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor SDK internals to use value objects, generics, field aggregation, OkHttp-style interceptors, and typed events — reducing method params from 5-7 to 1-2 and class fields from 18 to 6.

**Architecture:** Bottom-up refactoring: value objects first, then transport interface, then cache, then interceptors, then events. Each phase compiles and passes tests independently. Business API (client.check(), client.on().grant()) signatures unchanged throughout.

**Tech Stack:** Java 21, records, sealed interfaces, Caffeine, Resilience4j, gRPC

**Spec:** `docs/superpowers/specs/2026-04-05-sdk-architecture-refactor-design.md`

---

## File Map

### New Files (Phase 1)
- `src/main/java/com/authcses/sdk/model/ResourceRef.java` — resource reference record
- `src/main/java/com/authcses/sdk/model/SubjectRef.java` — subject reference record (replaces Ref.java)
- `src/main/java/com/authcses/sdk/model/Permission.java` — permission value object
- `src/main/java/com/authcses/sdk/model/Relation.java` — relation value object
- `src/main/java/com/authcses/sdk/model/CheckKey.java` — cache key record
- `src/main/java/com/authcses/sdk/model/CaveatRef.java` — caveat reference record
- `src/main/java/com/authcses/sdk/model/CheckRequest.java` — check request object
- `src/main/java/com/authcses/sdk/model/WriteRequest.java` — write request object
- `src/main/java/com/authcses/sdk/model/LookupSubjectsRequest.java` — lookup subjects request
- `src/main/java/com/authcses/sdk/model/LookupResourcesRequest.java` — lookup resources request
- `src/main/java/com/authcses/sdk/SdkInfrastructure.java` — infra field aggregation
- `src/main/java/com/authcses/sdk/SdkObservability.java` — observability field aggregation
- `src/main/java/com/authcses/sdk/SdkCaching.java` — caching field aggregation
- `src/main/java/com/authcses/sdk/SdkConfig.java` — config field aggregation

### New Files (Phase 2)
- `src/main/java/com/authcses/sdk/cache/Cache.java` — generic Cache\<K,V\> interface
- `src/main/java/com/authcses/sdk/cache/CacheStats.java` — cache statistics record
- `src/main/java/com/authcses/sdk/cache/IndexedCache.java` — cache with secondary index
- `src/main/java/com/authcses/sdk/cache/CaffeineCache.java` — generic Caffeine implementation
- `src/main/java/com/authcses/sdk/cache/TieredCache.java` — generic L1+L2 cache
- `src/main/java/com/authcses/sdk/cache/NoopCache.java` — generic no-op cache
- `src/main/java/com/authcses/sdk/spi/AttributeKey.java` — type-safe attribute key

### New Files (Phase 3)
- `src/main/java/com/authcses/sdk/spi/CheckChain.java` — OkHttp-style check chain
- `src/main/java/com/authcses/sdk/spi/WriteChain.java` — OkHttp-style write chain
- `src/main/java/com/authcses/sdk/transport/RealCheckChain.java` — chain implementation
- `src/main/java/com/authcses/sdk/transport/RealWriteChain.java` — chain implementation

### New Files (Phase 4)
- `src/main/java/com/authcses/sdk/event/ClientEvent.java` — sealed event record
- `src/main/java/com/authcses/sdk/event/CacheEvent.java` — sealed event record
- `src/main/java/com/authcses/sdk/event/CircuitEvent.java` — sealed event record
- `src/main/java/com/authcses/sdk/event/TransportEvent.java` — sealed event record
- `src/main/java/com/authcses/sdk/event/WatchEvent.java` — sealed event record
- `src/main/java/com/authcses/sdk/event/EventListener.java` — generic typed listener
- `src/main/java/com/authcses/sdk/event/EventBus.java` — typed event bus interface
- `src/main/java/com/authcses/sdk/event/DefaultEventBus.java` — implementation

### Modified Files (Phase 1 — biggest batch)
- `src/main/java/com/authcses/sdk/transport/SdkTransport.java` — all method signatures + RelationshipUpdate
- `src/main/java/com/authcses/sdk/transport/ForwardingTransport.java` — all 14 delegations
- `src/main/java/com/authcses/sdk/transport/GrpcTransport.java` — all implementations
- `src/main/java/com/authcses/sdk/transport/InMemoryTransport.java` — all implementations
- `src/main/java/com/authcses/sdk/transport/CachedTransport.java` — check/write overrides
- `src/main/java/com/authcses/sdk/transport/ResilientTransport.java` — all overrides
- `src/main/java/com/authcses/sdk/transport/InstrumentedTransport.java` — all overrides
- `src/main/java/com/authcses/sdk/transport/PolicyAwareConsistencyTransport.java` — all overrides
- `src/main/java/com/authcses/sdk/transport/CoalescingTransport.java` — check override
- `src/main/java/com/authcses/sdk/transport/InterceptorTransport.java` — all overrides
- `src/main/java/com/authcses/sdk/AuthCsesClient.java` — fields, constructor, bridge methods
- `src/main/java/com/authcses/sdk/ResourceHandle.java` — fields, action classes
- `src/main/java/com/authcses/sdk/ResourceFactory.java` — delegate methods
- `src/main/java/com/authcses/sdk/CrossResourceBatchBuilder.java` — RelationshipUpdate usage
- `src/main/java/com/authcses/sdk/LookupQuery.java` — transport calls

### Deleted Files
- `src/main/java/com/authcses/sdk/model/Ref.java` — replaced by SubjectRef
- `src/main/java/com/authcses/sdk/cache/CheckCache.java` — replaced by Cache\<K,V\>
- `src/main/java/com/authcses/sdk/cache/CaffeineCheckCache.java` — replaced by CaffeineCache
- `src/main/java/com/authcses/sdk/cache/PolicyAwareCheckCache.java` — merged into CaffeineCache with Expiry
- `src/main/java/com/authcses/sdk/cache/NoopCheckCache.java` — replaced by NoopCache
- `src/main/java/com/authcses/sdk/cache/TwoLevelCache.java` — replaced by TieredCache

---

## Phase 1: Value Objects + Field Aggregation

### Task 1: Create Value Object Records

**Files:**
- Create: `src/main/java/com/authcses/sdk/model/ResourceRef.java`
- Create: `src/main/java/com/authcses/sdk/model/SubjectRef.java`
- Create: `src/main/java/com/authcses/sdk/model/Permission.java`
- Create: `src/main/java/com/authcses/sdk/model/Relation.java`
- Create: `src/main/java/com/authcses/sdk/model/CheckKey.java`
- Create: `src/main/java/com/authcses/sdk/model/CaveatRef.java`
- Test: `src/test/java/com/authcses/sdk/model/ValueObjectTest.java`

- [ ] **Step 1: Write tests for value objects**

```java
package com.authcses.sdk.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ValueObjectTest {

    @Test void resourceRef_of() {
        var ref = ResourceRef.of("document", "doc-1");
        assertThat(ref.type()).isEqualTo("document");
        assertThat(ref.id()).isEqualTo("doc-1");
    }

    @Test void resourceRef_rejectsNull() {
        assertThatThrownBy(() -> ResourceRef.of(null, "id"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void subjectRef_user() {
        var ref = SubjectRef.user("alice");
        assertThat(ref.type()).isEqualTo("user");
        assertThat(ref.id()).isEqualTo("alice");
        assertThat(ref.relation()).isNull();
    }

    @Test void subjectRef_parse() {
        var ref = SubjectRef.parse("department:eng#all_members");
        assertThat(ref.type()).isEqualTo("department");
        assertThat(ref.id()).isEqualTo("eng");
        assertThat(ref.relation()).isEqualTo("all_members");
    }

    @Test void subjectRef_wildcard() {
        var ref = SubjectRef.wildcard("user");
        assertThat(ref.type()).isEqualTo("user");
        assertThat(ref.id()).isEqualTo("*");
    }

    @Test void checkKey_equality() {
        var k1 = CheckKey.of(ResourceRef.of("document", "d1"), Permission.of("view"), SubjectRef.user("alice"));
        var k2 = CheckKey.of(ResourceRef.of("document", "d1"), Permission.of("view"), SubjectRef.user("alice"));
        assertThat(k1).isEqualTo(k2);
        assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    }

    @Test void checkKey_resourceIndex() {
        var k = CheckKey.of(ResourceRef.of("document", "d1"), Permission.of("view"), SubjectRef.user("alice"));
        assertThat(k.resourceIndex()).isEqualTo("document:d1");
    }

    @Test void permission_of() {
        assertThat(Permission.of("view").name()).isEqualTo("view");
    }

    @Test void relation_of() {
        assertThat(Relation.of("editor").name()).isEqualTo("editor");
    }

    @Test void caveatRef() {
        var c = new CaveatRef("ip_range", java.util.Map.of("allowed", "10.0.0.0/8"));
        assertThat(c.name()).isEqualTo("ip_range");
        assertThat(c.context()).containsKey("allowed");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.authcses.sdk.model.ValueObjectTest" 2>&1 | tail -5`
Expected: compilation error (classes don't exist)

- [ ] **Step 3: Implement all value objects**

Create `ResourceRef.java`:
```java
package com.authcses.sdk.model;

import java.util.Objects;

public record ResourceRef(String type, String id) {
    public ResourceRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }
    public static ResourceRef of(String type, String id) {
        return new ResourceRef(type, id);
    }
}
```

Create `SubjectRef.java`:
```java
package com.authcses.sdk.model;

import java.util.Objects;

public record SubjectRef(String type, String id, String relation) {
    public SubjectRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }
    public static SubjectRef user(String id) { return new SubjectRef("user", id, null); }
    public static SubjectRef wildcard(String type) { return new SubjectRef(type, "*", null); }
    public static SubjectRef of(String type, String id, String relation) { return new SubjectRef(type, id, relation); }

    public static SubjectRef parse(String ref) {
        // "department:eng#all_members" or "user:alice" or "user:*"
        int colonIdx = ref.indexOf(':');
        if (colonIdx < 0) throw new IllegalArgumentException("Invalid ref: " + ref);
        String type = ref.substring(0, colonIdx);
        String rest = ref.substring(colonIdx + 1);
        int hashIdx = rest.indexOf('#');
        if (hashIdx >= 0) {
            return new SubjectRef(type, rest.substring(0, hashIdx), rest.substring(hashIdx + 1));
        }
        return new SubjectRef(type, rest, null);
    }

    /** Format as "type:id" or "type:id#relation" */
    public String toRefString() {
        if (relation != null) return type + ":" + id + "#" + relation;
        return type + ":" + id;
    }
}
```

Create `Permission.java`:
```java
package com.authcses.sdk.model;

import java.util.Objects;

public record Permission(String name) {
    public Permission { Objects.requireNonNull(name, "name"); }
    public static Permission of(String name) { return new Permission(name); }
}
```

Create `Relation.java`:
```java
package com.authcses.sdk.model;

import java.util.Objects;

public record Relation(String name) {
    public Relation { Objects.requireNonNull(name, "name"); }
    public static Relation of(String name) { return new Relation(name); }
}
```

Create `CheckKey.java`:
```java
package com.authcses.sdk.model;

public record CheckKey(ResourceRef resource, Permission permission, SubjectRef subject) {
    public static CheckKey of(ResourceRef resource, Permission permission, SubjectRef subject) {
        return new CheckKey(resource, permission, subject);
    }
    /** Index key for O(k) resource invalidation */
    public String resourceIndex() {
        return resource.type() + ":" + resource.id();
    }
}
```

Create `CaveatRef.java`:
```java
package com.authcses.sdk.model;

import java.util.Map;

public record CaveatRef(String name, Map<String, Object> context) {}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.authcses.sdk.model.ValueObjectTest" -v 2>&1 | tail -10`
Expected: all 9 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/authcses/sdk/model/{ResourceRef,SubjectRef,Permission,Relation,CheckKey,CaveatRef}.java \
        src/test/java/com/authcses/sdk/model/ValueObjectTest.java
git commit -m "feat: add value object records (ResourceRef, SubjectRef, Permission, Relation, CheckKey, CaveatRef)"
```

---

### Task 2: Create Request Objects (except WriteRequest — deferred to Task 4)

Note: WriteRequest depends on the refactored RelationshipUpdate (Task 4), so it's created there.

**Files:**
- Create: `src/main/java/com/authcses/sdk/model/CheckRequest.java`
- Create: `src/main/java/com/authcses/sdk/model/LookupSubjectsRequest.java`
- Create: `src/main/java/com/authcses/sdk/model/LookupResourcesRequest.java`
- Test: `src/test/java/com/authcses/sdk/model/RequestObjectTest.java`

- [ ] **Step 1: Write tests**

```java
package com.authcses.sdk.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequestObjectTest {

    @Test void checkRequest_of() {
        var req = CheckRequest.of(
            ResourceRef.of("document", "d1"),
            Permission.of("view"),
            SubjectRef.user("alice"),
            Consistency.full()
        );
        assertThat(req.resource().type()).isEqualTo("document");
        assertThat(req.permission().name()).isEqualTo("view");
        assertThat(req.subject().id()).isEqualTo("alice");
        assertThat(req.consistency()).isInstanceOf(Consistency.Full.class);
        assertThat(req.caveatContext()).isNull();
    }

    @Test void checkRequest_from_bridgesStrings() {
        var req = CheckRequest.from("document", "d1", "view", "user", "alice", Consistency.minimizeLatency());
        assertThat(req.resource()).isEqualTo(ResourceRef.of("document", "d1"));
        assertThat(req.subject()).isEqualTo(SubjectRef.of("user", "alice", null));
    }

    @Test void checkRequest_toKey() {
        var req = CheckRequest.from("document", "d1", "view", "user", "alice", Consistency.full());
        var key = req.toKey();
        assertThat(key.resource()).isEqualTo(ResourceRef.of("document", "d1"));
        assertThat(key.permission()).isEqualTo(Permission.of("view"));
    }

    @Test void lookupSubjectsRequest() {
        var req = new LookupSubjectsRequest(ResourceRef.of("document", "d1"), Permission.of("view"), "user", 100);
        assertThat(req.resource().id()).isEqualTo("d1");
        assertThat(req.subjectType()).isEqualTo("user");
    }

    @Test void lookupResourcesRequest() {
        var req = new LookupResourcesRequest("document", Permission.of("view"), SubjectRef.user("alice"), 100);
        assertThat(req.resourceType()).isEqualTo("document");
        assertThat(req.subject().id()).isEqualTo("alice");
    }
}
```

- [ ] **Step 2: Run test — expect compilation failure**

- [ ] **Step 3: Implement request objects**

Create `CheckRequest.java`:
```java
package com.authcses.sdk.model;

import java.util.Map;

public record CheckRequest(
    ResourceRef resource,
    Permission permission,
    SubjectRef subject,
    Consistency consistency,
    Map<String, Object> caveatContext
) {
    public static CheckRequest of(ResourceRef r, Permission p, SubjectRef s, Consistency c) {
        return new CheckRequest(r, p, s, c, null);
    }
    public static CheckRequest from(String resType, String resId, String perm,
                                     String subType, String subId, Consistency c) {
        return new CheckRequest(ResourceRef.of(resType, resId), Permission.of(perm),
                                SubjectRef.of(subType, subId, null), c, null);
    }
    public CheckKey toKey() { return new CheckKey(resource, permission, subject); }
}
```

Create `WriteRequest.java`:
```java
package com.authcses.sdk.model;

import com.authcses.sdk.transport.SdkTransport.RelationshipUpdate;
import java.util.List;

public record WriteRequest(List<RelationshipUpdate> updates, Consistency consistency) {
    public WriteRequest(List<RelationshipUpdate> updates) {
        this(updates, null);
    }
}
```

Create `LookupSubjectsRequest.java`:
```java
package com.authcses.sdk.model;

public record LookupSubjectsRequest(ResourceRef resource, Permission permission, String subjectType, int limit) {
    public LookupSubjectsRequest(ResourceRef resource, Permission permission, String subjectType) {
        this(resource, permission, subjectType, 0);
    }
}
```

Create `LookupResourcesRequest.java`:
```java
package com.authcses.sdk.model;

public record LookupResourcesRequest(String resourceType, Permission permission, SubjectRef subject, int limit) {
    public LookupResourcesRequest(String resourceType, Permission permission, SubjectRef subject) {
        this(resourceType, permission, subject, 0);
    }
}
```

- [ ] **Step 4: Run tests — all PASS**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/authcses/sdk/model/{CheckRequest,WriteRequest,LookupSubjectsRequest,LookupResourcesRequest}.java \
        src/test/java/com/authcses/sdk/model/RequestObjectTest.java
git commit -m "feat: add request object records (CheckRequest, WriteRequest, LookupSubjectsRequest, LookupResourcesRequest)"
```

---

### Task 3: Create Field Aggregation Classes

**Files:**
- Create: `src/main/java/com/authcses/sdk/SdkInfrastructure.java`
- Create: `src/main/java/com/authcses/sdk/SdkObservability.java`
- Create: `src/main/java/com/authcses/sdk/SdkCaching.java`
- Create: `src/main/java/com/authcses/sdk/SdkConfig.java`

- [ ] **Step 1: Create SdkInfrastructure (class, not record — has mutable state)**

```java
package com.authcses.sdk;

import com.authcses.sdk.lifecycle.LifecycleManager;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SdkInfrastructure implements AutoCloseable {
    private final io.grpc.ManagedChannel channel;
    private final ScheduledExecutorService scheduler;
    private final Executor asyncExecutor;
    private final LifecycleManager lifecycle;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Thread shutdownHookRef;

    public SdkInfrastructure(io.grpc.ManagedChannel channel, ScheduledExecutorService scheduler,
                              Executor asyncExecutor, LifecycleManager lifecycle) {
        this.channel = channel;
        this.scheduler = scheduler;
        this.asyncExecutor = asyncExecutor;
        this.lifecycle = lifecycle;
    }

    public io.grpc.ManagedChannel channel() { return channel; }
    public ScheduledExecutorService scheduler() { return scheduler; }
    public Executor asyncExecutor() { return asyncExecutor; }
    public LifecycleManager lifecycle() { return lifecycle; }
    public boolean isClosed() { return closed.get(); }
    public boolean markClosed() { return closed.compareAndSet(false, true); }
    public void setShutdownHook(Thread hook) { this.shutdownHookRef = hook; }
    public Thread shutdownHook() { return shutdownHookRef; }

    @Override
    public void close() {
        // Actual close logic will be in AuthCsesClient.close() which delegates here
    }
}
```

- [ ] **Step 2: Create SdkObservability (record)**

```java
package com.authcses.sdk;

import com.authcses.sdk.event.SdkEventBus;
import com.authcses.sdk.metrics.SdkMetrics;
import com.authcses.sdk.telemetry.TelemetryReporter;

public record SdkObservability(
    SdkMetrics metrics,
    SdkEventBus eventBus,
    TelemetryReporter telemetry
) {
    public SdkObservability {
        java.util.Objects.requireNonNull(metrics, "metrics");
        java.util.Objects.requireNonNull(eventBus, "eventBus");
    }
}
```

- [ ] **Step 3: Create SdkCaching (record)**

```java
package com.authcses.sdk;

import com.authcses.sdk.cache.CheckCache;
import com.authcses.sdk.cache.SchemaCache;
import com.authcses.sdk.transport.WatchCacheInvalidator;
import com.authcses.sdk.watch.WatchDispatcher;

public record SdkCaching(
    CheckCache checkCache,
    SchemaCache schemaCache,
    WatchCacheInvalidator watchInvalidator,
    WatchDispatcher watchDispatcher
) {
    public CacheHandle handle() { return new CacheHandle(checkCache); }
}
```

- [ ] **Step 4: Create SdkConfig (record)**

```java
package com.authcses.sdk;

import com.authcses.sdk.policy.PolicyRegistry;

public record SdkConfig(
    String defaultSubjectType,
    PolicyRegistry policies,
    boolean coalescingEnabled,
    boolean virtualThreads
) {}
```

- [ ] **Step 5: Compile and commit**

Run: `./gradlew compileJava 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

```bash
git add src/main/java/com/authcses/sdk/{SdkInfrastructure,SdkObservability,SdkCaching,SdkConfig}.java
git commit -m "feat: add field aggregation classes (SdkInfrastructure, SdkObservability, SdkCaching, SdkConfig)"
```

---

### Task 4: Refactor SdkTransport Interface + RelationshipUpdate

This is the critical task — changes the interface that all 8 implementations depend on. Must be done atomically with Task 5.

**Files:**
- Modify: `src/main/java/com/authcses/sdk/transport/SdkTransport.java`

- [ ] **Step 1: Rewrite SdkTransport interface to use request objects**

Change all method signatures from String params to request objects. Refactor `RelationshipUpdate` nested record fields to use value objects. **Also create WriteRequest here** (it depends on the new RelationshipUpdate shape). This step will break compilation of all implementations — that's expected, Task 5 fixes them.

Key changes:
- `check(6 strings)` → `check(CheckRequest)`
- `checkBulk(6 params)` → `checkBulk(CheckRequest, List<SubjectRef>)`
- `lookupSubjects(5-6 params)` → `lookupSubjects(LookupSubjectsRequest)`
- `lookupResources(5-6 params)` → `lookupResources(LookupResourcesRequest)`
- `deleteByFilter(5 strings)` → `deleteByFilter(ResourceRef, SubjectRef, Relation)`
- `expand(4 params)` → `expand(ResourceRef, Permission, Consistency)`
- `readRelationships(4 params)` → `readRelationships(ResourceRef, Relation, Consistency)`
- `RelationshipUpdate` fields: 10 → 6 (ResourceRef + Relation + SubjectRef + CaveatRef + Instant)
- Create `WriteRequest.java` using the new RelationshipUpdate

- [ ] **Step 2: Do NOT compile yet — proceed to Task 5**

---

### Task 5: Update All Transport Implementations (atomic with Task 4)

**Files:**
- Modify: `src/main/java/com/authcses/sdk/transport/ForwardingTransport.java`
- Modify: `src/main/java/com/authcses/sdk/transport/GrpcTransport.java`
- Modify: `src/main/java/com/authcses/sdk/transport/InMemoryTransport.java`
- Modify: `src/main/java/com/authcses/sdk/transport/CachedTransport.java`
- Modify: `src/main/java/com/authcses/sdk/transport/ResilientTransport.java`
- Modify: `src/main/java/com/authcses/sdk/transport/InstrumentedTransport.java`
- Modify: `src/main/java/com/authcses/sdk/transport/PolicyAwareConsistencyTransport.java`
- Modify: `src/main/java/com/authcses/sdk/transport/CoalescingTransport.java`
- Modify: `src/main/java/com/authcses/sdk/transport/InterceptorTransport.java`
- Modify: `src/main/java/com/authcses/sdk/transport/WatchCacheInvalidator.java`
- Modify: `src/main/java/com/authcses/sdk/transport/TokenTracker.java`

- [ ] **Step 1: Update ForwardingTransport — all 14 delegate methods**

Change every method signature to match new SdkTransport. Each method simply delegates: `return delegate().check(request);`

- [ ] **Step 2: Update all 6 decorators + aggregate ResilientTransport fields**

Each decorator overrides specific methods. Update their signatures and internal logic to use request objects instead of string params.

For ResilientTransport, also aggregate fields into ResilienceRegistry:
```java
// 11 fields → 3
private final SdkTransport delegate;
private final ResilienceRegistry registry; // breakers + retries + policies
private final SdkEventBus eventBus;

static class ResilienceRegistry {
    private final PolicyRegistry policies;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Retry> retries = new ConcurrentHashMap<>();
    CircuitBreaker breakerFor(String resourceType) { ... }
    Retry retryFor(String resourceType) { ... }
}
```

For CachedTransport.check():

```java
// Before
public CheckResult check(String resourceType, String resourceId, String permission,
                         String subjectType, String subjectId, Consistency consistency) {
    if (consistency instanceof Consistency.MinimizeLatency) {
        var cached = cache.get(resourceType, resourceId, permission, subjectType, subjectId);
        ...
    }
}

// After
public CheckResult check(CheckRequest request) {
    if (request.consistency() instanceof Consistency.MinimizeLatency) {
        var key = request.toKey();
        var cached = cache.get(key);
        ...
    }
}
```

- [ ] **Step 3: Update GrpcTransport — convert request objects to protobuf**

GrpcTransport.check() extracts fields from CheckRequest to build the gRPC CheckPermissionRequest protobuf. RelationshipUpdate → gRPC RelationshipUpdate conversion uses the new value objects.

- [ ] **Step 4: Update InMemoryTransport similarly**

- [ ] **Step 5: Compile**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (all implementations updated)

---

### Task 6: Bridge Layer — AuthCsesClient + ResourceHandle + ResourceFactory

**Files:**
- Modify: `src/main/java/com/authcses/sdk/AuthCsesClient.java` — fields, constructor, bridge methods
- Modify: `src/main/java/com/authcses/sdk/ResourceHandle.java` — action classes use new types internally
- Modify: `src/main/java/com/authcses/sdk/ResourceFactory.java` — delegate methods
- Modify: `src/main/java/com/authcses/sdk/CrossResourceBatchBuilder.java` — RelationshipUpdate
- Modify: `src/main/java/com/authcses/sdk/LookupQuery.java` — transport calls
- Modify: `src/main/java/com/authcses/sdk/CacheHandle.java` — use Cache\<CheckKey, CheckResult\>
- Modify: `src/main/java/com/authcses/sdk/model/RelationshipChange.java` — use ResourceRef, SubjectRef
- Modify: `src/main/java/com/authcses/sdk/model/Tuple.java` — use ResourceRef, SubjectRef
- Delete: `src/main/java/com/authcses/sdk/model/Ref.java` — replaced by SubjectRef
- Update: all existing transport test files to use new request objects

- [ ] **Step 1: Refactor AuthCsesClient fields to use aggregation classes**

Replace 18 fields with 6:
```java
private final SdkTransport transport;
private final SdkInfrastructure infra;
private final SdkObservability observability;
private final SdkCaching caching;
private final SdkConfig config;
private final ConcurrentHashMap<String, ResourceFactory> factories = new ConcurrentHashMap<>();
```

Update constructor, update Builder.build() to create aggregation objects, update close() to delegate to infra.close().

- [ ] **Step 2: Update bridge methods in AuthCsesClient**

The public API `check(String, String, String, String, Consistency)` stays unchanged. Internal implementation converts to CheckRequest:

```java
public boolean check(String type, String id, String permission, String userId,
                     Consistency consistency) {
    // L0 cache fast path
    if (consistency instanceof Consistency.MinimizeLatency && caching.checkCache() != null) {
        var key = CheckKey.of(ResourceRef.of(type, id), Permission.of(permission), SubjectRef.user(userId));
        var cached = caching.checkCache().getIfPresent(key);
        if (cached != null) return cached.hasPermission();
    }
    var request = CheckRequest.from(type, id, permission, config.defaultSubjectType(), userId, consistency);
    return transport.check(request).hasPermission();
}
```

- [ ] **Step 3: Update ResourceHandle action classes**

GrantAction, RevokeAction, CheckAction etc. internally use SubjectRef instead of Ref. Replace `Ref.parse()` with `SubjectRef.parse()`.

- [ ] **Step 4: Delete Ref.java, update CrossResourceBatchBuilder and LookupQuery**

- [ ] **Step 5: Compile and run full test suite**

Run: `./gradlew test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, all SDK unit tests pass

- [ ] **Step 6: Commit Phase 1**

```bash
git add -A
git commit -m "refactor: Phase 1 — value objects + field aggregation

- ResourceRef, SubjectRef, Permission, Relation, CheckKey, CaveatRef
- CheckRequest, WriteRequest, LookupSubjectsRequest, LookupResourcesRequest
- SdkTransport methods: 5-7 String params → 1-2 request objects
- AuthCsesClient: 18 fields → 6 (SdkInfrastructure, SdkObservability, SdkCaching, SdkConfig)
- RelationshipUpdate: 10 fields → 6 (value objects)
- Ref.java deleted, replaced by SubjectRef
- All 7 transport decorators updated
- Business API unchanged"
```

---

## Phase 2: Generic Cache + AttributeKey

### Task 7: Create Generic Cache\<K,V\> Interface + Implementations

**Files:**
- Create: `src/main/java/com/authcses/sdk/cache/Cache.java`
- Create: `src/main/java/com/authcses/sdk/cache/CacheStats.java`
- Create: `src/main/java/com/authcses/sdk/cache/IndexedCache.java`
- Create: `src/main/java/com/authcses/sdk/cache/CaffeineCache.java`
- Create: `src/main/java/com/authcses/sdk/cache/TieredCache.java`
- Create: `src/main/java/com/authcses/sdk/cache/NoopCache.java`
- Test: `src/test/java/com/authcses/sdk/cache/CacheTest.java`

- [ ] **Step 1: Write tests for Cache\<K,V\>**
- [ ] **Step 2: Implement Cache interface + CacheStats record**
- [ ] **Step 3: Implement IndexedCache\<K,V\> with secondary index**
- [ ] **Step 4: Implement CaffeineCache\<K,V\> (port from CaffeineCheckCache, generify)**
- [ ] **Step 5: Implement TieredCache\<K,V\> and NoopCache\<K,V\>**
- [ ] **Step 6: Run tests, commit**

### Task 8: Migrate CachedTransport + AuthCsesClient to Cache\<CheckKey, CheckResult\>

**Files:**
- Modify: `src/main/java/com/authcses/sdk/transport/CachedTransport.java`
- Modify: `src/main/java/com/authcses/sdk/AuthCsesClient.java` — checkCache field type
- Modify: `src/main/java/com/authcses/sdk/SdkCaching.java` — field type
- Modify: `src/main/java/com/authcses/sdk/transport/WatchCacheInvalidator.java`
- Delete: `src/main/java/com/authcses/sdk/cache/CheckCache.java`
- Delete: `src/main/java/com/authcses/sdk/cache/CaffeineCheckCache.java`
- Delete: `src/main/java/com/authcses/sdk/cache/PolicyAwareCheckCache.java`
- Delete: `src/main/java/com/authcses/sdk/cache/NoopCheckCache.java`
- Delete: `src/main/java/com/authcses/sdk/cache/TwoLevelCache.java`
- Modify: `src/main/java/com/authcses/sdk/CacheHandle.java` — use IndexedCache
- Update: existing cache test files

- [ ] **Step 1: Update SdkCaching + CacheHandle to use `Cache<CheckKey, CheckResult>`**
- [ ] **Step 2: Update CachedTransport to use `Cache<CheckKey, CheckResult>`**
- [ ] **Step 3: Update WatchCacheInvalidator to use IndexedCache**
- [ ] **Step 4: Update AuthCsesClient.Builder to create CaffeineCache**
- [ ] **Step 5: Delete old CheckCache + implementations + update test files atomically**
- [ ] **Step 6: Compile, run full test suite, commit**

### Task 9: Create AttributeKey\<T\>

**Files:**
- Create: `src/main/java/com/authcses/sdk/spi/AttributeKey.java`
- Modify: `src/main/java/com/authcses/sdk/spi/SdkInterceptor.java` — OperationContext uses AttributeKey
- Test: `src/test/java/com/authcses/sdk/spi/AttributeKeyTest.java`

- [ ] **Step 1: Write test for AttributeKey type safety**
- [ ] **Step 2: Implement AttributeKey\<T\>**
- [ ] **Step 3: Update OperationContext.getAttribute/setAttribute to use AttributeKey\<T\>**
- [ ] **Step 4: Update built-in interceptors**
- [ ] **Step 5: Run tests, commit Phase 2**

---

## Phase 3: OkHttp-Style Interceptor Chain

### Task 10: Create Chain Interfaces

**Files:**
- Create: `src/main/java/com/authcses/sdk/spi/CheckChain.java`
- Create: `src/main/java/com/authcses/sdk/spi/WriteChain.java`

- [ ] **Step 1: Define chain interfaces**
- [ ] **Step 2: Commit**

### Task 11: Implement Chain + Rewrite InterceptorTransport

**Files:**
- Create: `src/main/java/com/authcses/sdk/transport/RealCheckChain.java`
- Create: `src/main/java/com/authcses/sdk/transport/RealWriteChain.java`
- Modify: `src/main/java/com/authcses/sdk/spi/SdkInterceptor.java` — add interceptCheck/interceptWrite
- Modify: `src/main/java/com/authcses/sdk/transport/InterceptorTransport.java`

- [ ] **Step 1: Implement RealCheckChain (OkHttp-style index+1 chain)**
- [ ] **Step 2: Add interceptCheck(CheckChain) to SdkInterceptor with default that delegates to before/after for backward compat**
- [ ] **Step 3: Rewrite InterceptorTransport to create chain**
- [ ] **Step 4: Update built-in interceptors (ValidationInterceptor, DebugInterceptor, LogRedactionInterceptor, Resilience4jInterceptor)**
- [ ] **Step 5: Write chain-specific tests (short-circuit, request modification, exception handling)**
- [ ] **Step 6: Run full test suite, commit Phase 3**

---

## Phase 4: Transport Split + Typed Events

### Task 12: Split SdkTransport into Sub-Interfaces

**Files:**
- Create: `src/main/java/com/authcses/sdk/transport/SdkCheckTransport.java`
- Create: `src/main/java/com/authcses/sdk/transport/SdkWriteTransport.java`
- Create: `src/main/java/com/authcses/sdk/transport/SdkLookupTransport.java`
- Create: `src/main/java/com/authcses/sdk/transport/SdkReadTransport.java`
- Create: `src/main/java/com/authcses/sdk/transport/SdkExpandTransport.java`
- Modify: `src/main/java/com/authcses/sdk/transport/SdkTransport.java` — extends sub-interfaces

- [ ] **Step 1: Create 5 sub-interfaces, extract methods from SdkTransport**
- [ ] **Step 2: SdkTransport extends all 5 sub-interfaces**
- [ ] **Step 3: ForwardingTransport implements SdkTransport (no change to decorators)**
- [ ] **Step 4: Compile, commit**

### Task 13: Typed Event System

**Files:**
- Create: `src/main/java/com/authcses/sdk/event/SdkEvent.java` (rewrite as sealed interface)
- Create: `src/main/java/com/authcses/sdk/event/ClientEvent.java`
- Create: `src/main/java/com/authcses/sdk/event/CacheEvent.java`
- Create: `src/main/java/com/authcses/sdk/event/CircuitEvent.java`
- Create: `src/main/java/com/authcses/sdk/event/TransportEvent.java`
- Create: `src/main/java/com/authcses/sdk/event/WatchEvent.java`
- Create: `src/main/java/com/authcses/sdk/event/EventListener.java`
- Create: `src/main/java/com/authcses/sdk/event/EventBus.java`
- Create: `src/main/java/com/authcses/sdk/event/DefaultEventBus.java`
- Modify: `src/main/java/com/authcses/sdk/event/SdkEventBus.java` — implement EventBus
- Modify: all event publish sites

- [ ] **Step 1: Create sealed SdkEvent hierarchy + EventListener\<E\> + EventBus interface**
- [ ] **Step 2: Create DefaultEventBus implementing typed subscriptions**
- [ ] **Step 3: Update all event publish sites (ResilientTransport, WatchCacheInvalidator, LifecycleManager, etc.)**
- [ ] **Step 4: Delete obsolete files: SdkEventData.java, old SdkEventListener.java**
- [ ] **Step 5: Write typed event tests (subscribe by type, publish, verify listener receives correct type)**
- [ ] **Step 6: Update existing SdkEventBusTest**
- [ ] **Step 7: Run full test suite, commit Phase 4**

---

## Verification

After all 4 phases:

- [ ] Run: `./gradlew test 2>&1 | tail -5` — all SDK unit tests pass
- [ ] Run: `./gradlew :test-app:test --tests "com.authcses.testapp.SdkIntegrationTest" 2>&1 | tail -10` — integration tests pass
- [ ] Verify AuthCsesClient field count: `grep "private" AuthCsesClient.java | wc -l` — should be ~6
- [ ] Verify no String params in SdkTransport: `grep "String resourceType" SdkTransport.java` — should return 0
- [ ] Verify CheckCache deleted: `ls cache/CheckCache.java` — should not exist
