# Redisson Token Store Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in `sdk-redisson` gradle subproject that ships a `RedissonTokenStore` implementing `DistributedTokenStore`, so users can enable cross-JVM SESSION consistency by passing a single object instead of writing glue code.

**Architecture:** New standalone gradle module that depends on the main SDK (`:`) for the SPI plus `org.redisson:redisson` at runtime. Single public class `RedissonTokenStore` wraps a caller-owned `RedissonClient` and uses `RBucket<String>` with `StringCodec.INSTANCE` for set/get with TTL. All Redis errors are logged via `System.Logger` (matches `TokenTracker`) and swallowed per the SPI contract. Integration test uses Testcontainers (already a test dep on the root project, version 1.21.1).

**Tech Stack:** Java 21, Gradle, Redisson 3.27.x, Testcontainers 1.21.1, JUnit 5, AssertJ.

---

## File Structure

```
settings.gradle                                      # add include "sdk-redisson"
sdk-redisson/
  build.gradle                                       # standalone module, depends on :
  README.md                                          # 30-line wiring example + TTL guidance
  src/main/java/com/authx/sdk/redisson/
    RedissonTokenStore.java                          # ~80 lines
  src/test/java/com/authx/sdk/redisson/
    RedissonTokenStoreIT.java                        # Testcontainers Redis
CLAUDE.md                                            # add 1-line bullet under Project structure
```

**File responsibilities:**
- `sdk-redisson/build.gradle` — declares Redisson + test deps, depends on root project for SPI
- `RedissonTokenStore.java` — single class, no helpers, no statics beyond logger
- `RedissonTokenStoreIT.java` — five integration tests covering round-trip, TTL, prefix isolation, Redis-down resilience, missing key
- `README.md` — wiring snippet + which Redisson client config to use, plus link back to spec

**Note on logger choice:** Spec said `java.util.logging.Logger`, but the SDK transport package (notably `TokenTracker.java:27`) uses `System.Logger`. We'll use `System.Logger` for consistency with the SDK's existing logging stack — `java.util.logging` is the underlying handler, but `System.Logger` is the abstraction the rest of the SDK uses. Spec stays accurate in spirit (no SLF4J pulled).

---

## Tasks

### Task T001: Create module skeleton

**Files:**
- Create: `sdk-redisson/build.gradle`
- Modify: `settings.gradle` (add `include "sdk-redisson"`)

**Steps:**

1. Create `sdk-redisson/build.gradle`:

```gradle
plugins {
    id "java-library"
}

group = "io.github.authxkit"
version = rootProject.findProperty("sdkVersion") ?: "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
}

dependencies {
    // SPI (DistributedTokenStore) lives in the main SDK
    implementation(rootProject)

    // Redisson — Java Redis client used to back the SPI
    implementation("org.redisson:redisson:3.27.2")

    // Null-safety annotations
    implementation("org.jspecify:jspecify:1.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.testcontainers:testcontainers:1.21.1")
    testImplementation("org.testcontainers:junit-jupiter:1.21.1")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.12")
}

test {
    useJUnitPlatform()
    systemProperty "api.version", "1.43"  // Docker Desktop 29+ compat (matches root)
}
```

2. Append to `settings.gradle`:

```gradle
include "sdk-redisson"
```

3. Run `./gradlew :sdk-redisson:dependencies --configuration runtimeClasspath` and confirm Redisson 3.27.2 + main SDK appear, no error.

4. Commit: `git add sdk-redisson/build.gradle settings.gradle && git commit -m "build(sdk-redisson): scaffold module"`

### Task T002: Write failing integration test

**Files:**
- Create: `sdk-redisson/src/test/java/com/authx/sdk/redisson/RedissonTokenStoreIT.java`

**Steps:**

1. Create the test file with all five test cases up front (TDD round 1: tests should compile but fail to run since the class doesn't exist yet):

```java
package com.authx.sdk.redisson;

import com.authx.sdk.spi.DistributedTokenStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedissonTokenStoreIT {

    private static GenericContainer<?> redis;
    private static RedissonClient client;

    @BeforeAll
    static void up() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redis.start();
        Config c = new Config();
        c.useSingleServer().setAddress("redis://" + redis.getHost() + ":" + redis.getFirstMappedPort());
        client = Redisson.create(c);
    }

    @AfterAll
    static void down() {
        if (client != null) client.shutdown();
        if (redis != null) redis.stop();
    }

    @Test
    void roundTrip_setThenGet_returnsValue() {
        DistributedTokenStore store = new RedissonTokenStore(client, Duration.ofSeconds(30), "t1:");
        store.set("k", "v");
        assertThat(store.get("k")).isEqualTo("v");
    }

    @Test
    void ttl_keyExpires() throws InterruptedException {
        DistributedTokenStore store = new RedissonTokenStore(client, Duration.ofSeconds(1), "t2:");
        store.set("k", "v");
        Thread.sleep(1500);
        assertThat(store.get("k")).isNull();
    }

    @Test
    void prefixIsolation_twoStoresDoNotShareKeys() {
        DistributedTokenStore a = new RedissonTokenStore(client, Duration.ofSeconds(30), "ta:");
        DistributedTokenStore b = new RedissonTokenStore(client, Duration.ofSeconds(30), "tb:");
        a.set("k", "from-a");
        b.set("k", "from-b");
        assertThat(a.get("k")).isEqualTo("from-a");
        assertThat(b.get("k")).isEqualTo("from-b");
    }

    @Test
    void missingKey_returnsNull() {
        DistributedTokenStore store = new RedissonTokenStore(client, Duration.ofSeconds(30), "t4:");
        assertThat(store.get("never-set")).isNull();
    }

    @Test
    void redisDown_setAndGetDoNotThrow() {
        // Build a separate client pointed at the wrong port so every call fails fast.
        Config bad = new Config();
        bad.useSingleServer()
                .setAddress("redis://127.0.0.1:1")
                .setRetryAttempts(0)
                .setConnectTimeout(200)
                .setTimeout(200);
        RedissonClient deadClient = Redisson.create(bad);
        try {
            DistributedTokenStore store = new RedissonTokenStore(
                    deadClient, Duration.ofSeconds(30), "t5:");
            // SPI contract: must not throw
            store.set("k", "v");
            assertThat(store.get("k")).isNull();
        } finally {
            deadClient.shutdown();
        }
    }
}
```

2. Run the test, expect compilation failure on `RedissonTokenStore`:

```bash
./gradlew :sdk-redisson:test
```

Expected: `error: cannot find symbol class RedissonTokenStore`. This confirms the test exists and fails.

3. Do NOT commit yet — commit comes after the implementation lands so the failing test never enters main history.

### Task T003: Implement RedissonTokenStore to make the tests pass

**Files:**
- Create: `sdk-redisson/src/main/java/com/authx/sdk/redisson/RedissonTokenStore.java`

**Steps:**

1. Create the file:

```java
package com.authx.sdk.redisson;

import com.authx.sdk.spi.DistributedTokenStore;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Redisson-backed {@link DistributedTokenStore} for cross-JVM SESSION consistency.
 *
 * <p>Stores tokens as Redis strings under {@code keyPrefix + key} with TTL. The
 * caller owns the {@link RedissonClient} lifecycle (creates and shuts it down).
 *
 * <p>Honors the SPI contract: never throws on Redis errors. Failures are logged
 * at {@link Level#WARNING} and swallowed; {@link #get} returns {@code null} on miss
 * or error.
 *
 * <pre>
 * RedissonClient redis = Redisson.create(config);
 * DistributedTokenStore store = new RedissonTokenStore(
 *     redis, Duration.ofSeconds(60), "authx:token:");
 *
 * AuthxClient client = AuthxClient.builder()
 *     .connection(c -&gt; c.targets("...").presharedKey("..."))
 *     .extend(e -&gt; e.components(SdkComponents.builder()
 *             .tokenStore(store)
 *             .build()))
 *     .build();
 * </pre>
 */
public final class RedissonTokenStore implements DistributedTokenStore {

    private static final Logger LOG = System.getLogger(RedissonTokenStore.class.getName());

    private final RedissonClient client;
    private final long ttlMs;
    private final String prefix;

    public RedissonTokenStore(RedissonClient client, Duration ttl, String keyPrefix) {
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        this.ttlMs = ttl.toMillis();
        this.prefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
    }

    @Override
    public void set(String key, String token) {
        try {
            client.<String>getBucket(prefix + key, StringCodec.INSTANCE)
                  .set(token, ttlMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    () -> "RedissonTokenStore set failed for key " + key, e);
        }
    }

    @Override
    public @Nullable String get(String key) {
        try {
            return client.<String>getBucket(prefix + key, StringCodec.INSTANCE).get();
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    () -> "RedissonTokenStore get failed for key " + key, e);
            return null;
        }
    }
}
```

2. Run the tests:

```bash
./gradlew :sdk-redisson:test
```

Expected: all 5 tests pass. Note: `redisDown_setAndGetDoNotThrow` may take ~5s due to connect timeout — acceptable.

3. If `ttl_keyExpires` is flaky, increase sleep to 2000 ms. Do NOT increase ttl, since 1s is the realistic floor.

4. Commit:

```bash
git add sdk-redisson/src/
git commit -m "feat(sdk-redisson): RedissonTokenStore impl + integration tests"
```

### Task T004: Add README

**Files:**
- Create: `sdk-redisson/README.md`

**Steps:**

1. Create `sdk-redisson/README.md`:

````markdown
# sdk-redisson

Redisson-backed `DistributedTokenStore` for cross-JVM SESSION consistency in [AuthX SDK](../README.md).

## Why

The main SDK ships the `DistributedTokenStore` SPI but no implementation. Without one, ZedTokens are JVM-local and SESSION consistency breaks across SDK instances. This module wires Redisson into that SPI in ~80 lines.

## Add the dependency

```gradle
dependencies {
    implementation("io.github.authxkit:authx-spicedb-sdk:<version>")
    implementation("io.github.authxkit:authx-spicedb-sdk-redisson:<version>")
}
```

(Module is opt-in — main SDK does not depend on Redisson.)

## Wire it

```java
RedissonClient redis = Redisson.create(redissonConfig);  // you own the lifecycle

DistributedTokenStore store = new RedissonTokenStore(
        redis,
        Duration.ofSeconds(60),    // TTL — match your read-after-write SLA
        "authx:token:");           // key prefix — pick something app-unique

AuthxClient client = AuthxClient.builder()
        .connection(c -> c.targets("spicedb-1:50051", "spicedb-2:50051", "spicedb-3:50051")
                          .presharedKey("..."))
        .extend(e -> e.components(SdkComponents.builder()
                .tokenStore(store)
                .build()))
        .build();
```

## Behavior

- Set/get failures are logged at WARNING via `System.Logger` and swallowed (per SPI contract).
- `get` returns `null` on miss or error.
- TTL is reset on every `set`.
- Uses Redisson's `StringCodec` — no Kryo serialization overhead, no historical CVE surface.

## TTL guidance

Pick a TTL ≥ your worst-case read-after-write window. 60 s is a safe default for most cases; lower it (10–30 s) for chatty workloads, raise it (5 min) if writes are infrequent and you want to keep tokens warm across restarts.

## Redis client lifecycle

Caller owns the `RedissonClient`. Don't create one per `RedissonTokenStore`; reuse the same client app-wide.

## Spec

See [`specs/2026-04-16-redisson-token-store/spec.md`](../specs/2026-04-16-redisson-token-store/spec.md).
````

2. Commit: `git add sdk-redisson/README.md && git commit -m "docs(sdk-redisson): add README"`

### Task T005: Register module in root CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

**Steps:**

1. In `CLAUDE.md`, find the "Project structure" section's tree block. After the `cluster-test/` line, add:

```
sdk-redisson/                  # Optional Redisson-backed DistributedTokenStore
                               # (multi-JVM SESSION consistency)
```

2. Commit: `git add CLAUDE.md && git commit -m "docs: register sdk-redisson module in CLAUDE.md"`

### Task T006: Verify nothing in main SDK broke

**Files:** none (verification only)

**Steps:**

1. Run main SDK tests to confirm no regression:

```bash
./gradlew test -x :test-app:test -x :cluster-test:test -x :sdk-redisson:test
```

Expected: 799 pass, 0 fail (matches pre-change baseline).

2. Run full suite including new module:

```bash
./gradlew test -x :test-app:test -x :cluster-test:test
```

Expected: 804 pass (799 + 5), 0 fail. Docker must be running for `:sdk-redisson:test` to start the Redis container; if Docker is not available, exclude `:sdk-redisson:test` and note it as a manual verification step.

3. Run `./gradlew :sdk-redisson:javadoc` to confirm the public API documents cleanly:

```bash
./gradlew :sdk-redisson:javadoc
```

Expected: no errors.

4. No commit — this is verification only.

---

## Coverage check (spec → tasks)

| Spec requirement | Task(s) | Status |
|---|---|---|
| req-1 — separate `sdk-redisson` subproject | T001 | Covered |
| req-2 — public class `RedissonTokenStore` | T003 | Covered |
| req-3 — constructor `(client, ttl, keyPrefix)`, caller-owned lifecycle | T003 | Covered |
| req-4 — `RBucket<String>` + `StringCodec.INSTANCE` | T003 | Covered |
| req-5 — never throw on Redis failure, log+swallow | T003 + T002 (test t-4) | Covered |
| req-6 — key = prefix+key, TTL via `RBucket.set(value,ttl,TimeUnit.MS)` | T003 + T002 (test t-2, t-3) | Covered |
| req-7 — no background threads/listeners | T003 (impl is purely call-driven) | Covered |
| req-8 — Testcontainers IT covering 5 scenarios | T002 | Covered |
| req-9 — README with wiring example + TTL guidance | T004 | Covered |
| t-1..t-5 — integration test cases | T002 | Covered |
| settings.gradle update | T001 | Covered |
| CLAUDE.md update | T005 | Covered |

No gaps.

## Self-review notes

- **Pass 2 (placeholder scan):** No "TBD"/"TODO" in plan. Concrete code in every step. ✓
- **Pass 3 (type consistency):** `RedissonTokenStore(RedissonClient, Duration, String)` signature is identical across T002 (test instantiation) and T003 (definition). `client.getBucket(...)` + `StringCodec.INSTANCE` matches in both files. ✓
- **Pass 4 (dependency integrity):** T002 → T003 (test must precede impl per TDD). T004, T005 depend on T003 (impl exists). T006 depends on T003. No [P] tasks since changes are sequential. ✓
- **Pass 5 (contradiction scan):** Spec said `java.util.logging.Logger`; plan uses `System.Logger`. Documented in "File responsibilities" section above — `System.Logger` is the SDK convention (TokenTracker.java:27) and is backed by `java.util.logging` underneath, so the spec's intent (no SLF4J) is preserved. No actual contradiction. ✓
