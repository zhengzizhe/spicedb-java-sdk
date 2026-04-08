# 分布式集群部署 + 千万级压测 Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy monitoring stack, build a 3-instance Spring Boot cluster that bulk-imports 10M relationships and runs comprehensive benchmarks against a 2-SpiceDB + 3-CockroachDB cluster.

**Architecture:** Single Gradle submodule `cluster-test/` with Spring Boot 3.x. Three JVM instances launched with different ports, all round-robin to both SpiceDB nodes. Data generated as files and imported via SpiceDB's `ExperimentalServiceGrpc.bulkImportRelationships` streaming RPC. Benchmark engine uses virtual threads and HdrHistogram for latency tracking, exposing metrics via Micrometer/Prometheus.

**Tech Stack:** Java 21, Spring Boot 3.4.x, Micrometer + Prometheus, gRPC (authzed 1.5.4 `ExperimentalServiceGrpc`), HdrHistogram, Docker Compose (Prometheus + Grafana)

---

## File Structure

### Docker Compose + Monitoring (req-1)

```
deploy/
├── docker-compose.yml                                    # MODIFY: add prometheus + grafana services
├── prometheus/
│   └── prometheus.yml                                    # CREATE: scrape config
└── grafana/
    ├── provisioning/
    │   ├── datasources/
    │   │   └── prometheus.yml                            # CREATE: auto-configure Prometheus datasource
    │   └── dashboards/
    │       └── dashboard.yml                             # CREATE: auto-load dashboards
    └── dashboards/
        ├── spicedb.json                                  # CREATE: SpiceDB overview
        ├── cockroachdb.json                              # CREATE: CockroachDB cluster
        └── sdk-app.json                                  # CREATE: SDK/application layer
```

### cluster-test Module (req-2 through req-6)

```
cluster-test/
├── build.gradle                                          # CREATE
├── scripts/
│   ├── start-cluster.sh                                  # CREATE
│   └── stop-cluster.sh                                   # CREATE
└── src/main/
    ├── java/com/authx/cluster/
    │   ├── ClusterTestApp.java                           # CREATE: Spring Boot main
    │   ├── config/
    │   │   └── SdkConfig.java                            # CREATE: AuthxClient bean
    │   ├── generator/
    │   │   ├── DataModel.java                            # CREATE: entity counts, ID generators
    │   │   ├── RelationshipFileGenerator.java            # CREATE: generate .txt files
    │   │   └── BulkImporter.java                         # CREATE: gRPC streaming import
    │   ├── benchmark/
    │   │   ├── BenchmarkRunner.java                      # CREATE: executor, stats, reporting
    │   │   ├── BenchmarkResult.java                      # CREATE: TPS/p50/p99/errors record
    │   │   ├── ReadBenchmark.java                        # CREATE: B1 — check/lookup mix
    │   │   ├── WriteBenchmark.java                       # CREATE: B2 — grant/revoke
    │   │   ├── ConsistencyBenchmark.java                 # CREATE: B3 — cross-instance Watch
    │   │   ├── DeepInheritanceBenchmark.java             # CREATE: B4 — 20-layer check
    │   │   ├── BatchBenchmark.java                       # CREATE: B5 — batch ops
    │   │   └── FaultRecoveryBenchmark.java               # CREATE: B6 — kill node
    │   ├── verify/
    │   │   └── CorrectnessVerifier.java                  # CREATE: 5 correctness scenarios
    │   └── api/
    │       └── TestController.java                       # CREATE: REST endpoints
    └── resources/
        └── application.yml                               # CREATE: config

settings.gradle                                           # MODIFY: add include "cluster-test"
```

---

## Task Details

### Task T001: Register cluster-test submodule

**Files:**
- Modify: `settings.gradle`
- Create: `cluster-test/build.gradle`

**Steps:**

1. Add to `settings.gradle`:
```groovy
include "cluster-test"
```

2. Create `cluster-test/build.gradle`:
```groovy
plugins {
    id "java"
    id "org.springframework.boot" version "3.4.4"
    id "io.spring.dependency-management" version "1.1.7"
}

group = "com.authx"
version = "0.0.1"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // Direct gRPC for BulkImport (authzed already transitive from SDK)
    implementation("io.grpc:grpc-netty-shaded:1.72.0")
    implementation("com.authzed.api:authzed:1.5.4")
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = "UTF-8"
}
```

3. Run `./gradlew :cluster-test:dependencies` to verify resolution.

---

### Task T002: Spring Boot app + SDK config + application.yml

**Files:**
- Create: `cluster-test/src/main/java/com/authx/cluster/ClusterTestApp.java`
- Create: `cluster-test/src/main/java/com/authx/cluster/config/SdkConfig.java`
- Create: `cluster-test/src/main/resources/application.yml`

**Steps:**

1. Create `ClusterTestApp.java`:
```java
package com.authx.cluster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ClusterTestApp {
    public static void main(String[] args) {
        SpringApplication.run(ClusterTestApp.class, args);
    }
}
```

2. Create `application.yml`:
```yaml
server:
  port: ${SERVER_PORT:8091}

cluster:
  node-index: ${NODE_INDEX:0}
  node-count: 3

spicedb:
  targets: ${SPICEDB_TARGETS:localhost:50061,localhost:50062}
  preshared-key: ${SPICEDB_PSK:testkey}
  cache-enabled: true
  cache-max-size: 200000
  watch-invalidation: true
  virtual-threads: true

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  metrics:
    tags:
      node: ${NODE_INDEX:0}
```

3. Create `SdkConfig.java`:
```java
package com.authx.cluster.config;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.policy.CachePolicy;
import com.authx.sdk.policy.CircuitBreakerPolicy;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ResourcePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class SdkConfig {

    @Value("${spicedb.targets}")
    private String targets;

    @Value("${spicedb.preshared-key}")
    private String presharedKey;

    @Value("${spicedb.cache-enabled}")
    private boolean cacheEnabled;

    @Value("${spicedb.cache-max-size}")
    private long cacheMaxSize;

    @Value("${spicedb.watch-invalidation}")
    private boolean watchInvalidation;

    @Value("${spicedb.virtual-threads}")
    private boolean virtualThreads;

    @Bean(destroyMethod = "close")
    public AuthxClient authxClient() {
        String[] targetList = targets.split(",");
        return AuthxClient.builder()
                .connection(c -> c
                        .targets(targetList)
                        .presharedKey(presharedKey)
                        .requestTimeout(Duration.ofSeconds(30)))
                .cache(c -> c
                        .enabled(cacheEnabled)
                        .maxSize(cacheMaxSize)
                        .watchInvalidation(watchInvalidation))
                .features(f -> f
                        .virtualThreads(virtualThreads)
                        .telemetry(true))
                .extend(e -> e.policies(PolicyRegistry.builder()
                        .defaultPolicy(ResourcePolicy.builder()
                                .circuitBreaker(CircuitBreakerPolicy.disabled())
                                .cache(CachePolicy.of(Duration.ofSeconds(30)))
                                .build())
                        .build()))
                .build();
    }
}
```

4. Run `./gradlew :cluster-test:compileJava` to verify compilation.

---

### Task T003: DataModel + RelationshipFileGenerator

**Files:**
- Create: `cluster-test/src/main/java/com/authx/cluster/generator/DataModel.java`
- Create: `cluster-test/src/main/java/com/authx/cluster/generator/RelationshipFileGenerator.java`

**Steps:**

1. Create `DataModel.java` — constants and ID generators:
```java
package com.authx.cluster.generator;

public final class DataModel {
    public static final int USER_COUNT = 10_000;
    public static final int DEPT_COUNT = 500;
    public static final int DEPT_DEPTH = 5;      // 5 levels of nesting
    public static final int GROUP_COUNT = 200;
    public static final int ORG_COUNT = 10;
    public static final int SPACE_COUNT = 1_000;
    public static final int FOLDER_COUNT = 50_000;
    public static final int FOLDER_MAX_DEPTH = 20;
    public static final int DOC_COUNT = 500_000;

    public static String userId(int i)   { return "user-" + i; }
    public static String deptId(int i)   { return "dept-" + i; }
    public static String groupId(int i)  { return "group-" + i; }
    public static String orgId(int i)    { return "org-" + i; }
    public static String spaceId(int i)  { return "space-" + i; }
    public static String folderId(int i) { return "folder-" + i; }
    public static String docId(int i)    { return "doc-" + i; }

    private DataModel() {}
}
```

2. Create `RelationshipFileGenerator.java` — writes relationships to a file in 3 phases:

   - **Phase 1 (基础实体):** department#member, department#parent, group#member, organization#member/admin
   - **Phase 2 (空间结构):** space relations, folder#ancestor (flattened), folder#editor/viewer
   - **Phase 3 (文档+权限):** document#folder, document#space, document#viewer/editor/owner, document#link_viewer

   Each phase appends to the output file. Uses `BufferedWriter` for efficiency. Format per line: `resource_type:resource_id#relation@subject_type:subject_id`

   Key generation logic:
   - Departments: 500 total in 5 levels (100 per level). Level 0 has no parent. Level N's parent is in level N-1.
   - Folders: Build a tree with max depth 20. Each folder gets ancestor relations to ALL ancestors (not just parent).
   - Documents: Distribute across folders evenly. Each doc gets ~8 permission relations (random user assignment).
   - Link sharing: 50% of docs get `link_viewer@user:*`.

   Returns total relationship count.

3. Run `./gradlew :cluster-test:compileJava`.

---

### Task T004: BulkImporter (gRPC streaming)

**Files:**
- Create: `cluster-test/src/main/java/com/authx/cluster/generator/BulkImporter.java`

**Steps:**

1. Create `BulkImporter.java` — reads the generated file line by line and streams to SpiceDB:

```java
package com.authx.cluster.generator;

import com.authzed.api.v1.ExperimentalServiceGrpc;
import com.authzed.api.v1.BulkImportRelationshipsRequest;
import com.authzed.api.v1.BulkImportRelationshipsResponse;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.Relationship;
import com.authzed.api.v1.SubjectReference;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
```

   Core logic:
   - Opens a gRPC channel to one SpiceDB target (not round-robin — BulkImport goes to one node).
   - Creates `ExperimentalServiceGrpc.newStub(channel)` (async stub for streaming).
   - Calls `bulkImportRelationships()` to get a `StreamObserver<BulkImportRelationshipsRequest>`.
   - Reads the file line by line. Parses each line with regex `^(\w+):(.+)#(\w+)@(\w+):(.+)$` into a `Relationship` proto.
   - Batches 1000 relationships per `BulkImportRelationshipsRequest` message.
   - Logs progress every 100,000 relationships.
   - On completion, the response returns the total imported count.

2. Run `./gradlew :cluster-test:compileJava`.

---

### Task T005: TestController — generate + import + status endpoints

**Files:**
- Create: `cluster-test/src/main/java/com/authx/cluster/api/TestController.java`

**Steps:**

1. Create `TestController.java` with endpoints:
   - `POST /test/generate` — calls `RelationshipFileGenerator`, returns `{ "count": N, "file": "path", "durationMs": M }`
   - `POST /test/import` — calls `BulkImporter`, returns `{ "imported": N, "durationMs": M }`
   - `GET /test/status` — returns current state (IDLE / GENERATING / IMPORTING / BENCHMARKING / etc.) and progress
   - `POST /test/benchmark/{scenario}` — triggers a benchmark scenario (B1-B6)
   - `POST /test/benchmark/all` — triggers all scenarios sequentially
   - `GET /test/benchmark/report` — returns latest benchmark results
   - `POST /test/verify` — runs correctness verification

   Uses `@Value("${cluster.node-index}")` to identify this instance.

   State is tracked in an `AtomicReference<TestState>` with fields: status, progress, lastResult.

2. Run `./gradlew :cluster-test:compileJava`.

---

### Task T006: BenchmarkRunner + BenchmarkResult + ReadBenchmark (B1)

**Files:**
- Create: `cluster-test/src/main/java/com/authx/cluster/benchmark/BenchmarkResult.java`
- Create: `cluster-test/src/main/java/com/authx/cluster/benchmark/BenchmarkRunner.java`
- Create: `cluster-test/src/main/java/com/authx/cluster/benchmark/ReadBenchmark.java`

**Steps:**

1. Create `BenchmarkResult.java` — a record:
```java
package com.authx.cluster.benchmark;

public record BenchmarkResult(
    String scenario,
    long totalOps,
    double tps,
    long p50Us,
    long p99Us,
    long p999Us,
    long errors,
    double errorRate,
    long durationMs
) {}
```

2. Create `BenchmarkRunner.java` — the shared executor:
   - Constructor takes `threads`, `durationSeconds`, `warmupSeconds` from config.
   - `run(Runnable operation)` method: starts N virtual threads, each running the operation in a loop for the specified duration. Uses `HdrHistogram.Recorder` for lock-free latency tracking.
   - After warmup period, resets the histogram.
   - After full duration, collects stats and returns `BenchmarkResult`.
   - Logs TPS every 5 seconds during the run.

3. Create `ReadBenchmark.java` — B1 scenario:
   - Constructor takes `AuthxClient` and `DataModel` references.
   - `run(BenchmarkRunner runner)` returns `BenchmarkResult`.
   - Operation: picks a random document ID + random user ID + random permission (view/edit/comment), calls `client.on("document").resource(docId).check(perm).by(userId).hasPermission()`.
   - Mix: 70% check, 10% checkAll, 10% lookup subjects, 5% lookup resources, 5% write (grant+revoke).

4. Run `./gradlew :cluster-test:compileJava`.

---

### Task T007: WriteBenchmark (B2) + ConsistencyBenchmark (B3)

**Files:**
- Create: `cluster-test/src/main/java/com/authx/cluster/benchmark/WriteBenchmark.java`
- Create: `cluster-test/src/main/java/com/authx/cluster/benchmark/ConsistencyBenchmark.java`

**Steps:**

1. Create `WriteBenchmark.java` — B2:
   - Each op: grant a relation on a random document to a random user, then immediately revoke it.
   - Uses `client.on("document").resource(docId).grant("viewer").to(userId)` then revoke.
   - Tracks write TPS and conflict/error rate.

2. Create `ConsistencyBenchmark.java` — B3:
   - Not a throughput test — a correctness/latency test.
   - Creates a new unique document, grants viewer to a specific user via client.
   - Then polls `check("view").by(user)` on the SAME client (should pass via fullConsistency).
   - Also polls from a DIFFERENT REST endpoint (another instance) via HTTP call to `http://localhost:{otherPort}/doc/check`.
   - Records time until the other instance returns `allowed=true` (Watch invalidation delay).
   - Repeats N times, reports p50/p99 of Watch delay.

3. Run `./gradlew :cluster-test:compileJava`.

---

### Task T008: DeepInheritanceBenchmark (B4) + BatchBenchmark (B5) + FaultRecoveryBenchmark (B6)

**Files:**
- Create: `cluster-test/src/main/java/com/authx/cluster/benchmark/DeepInheritanceBenchmark.java`
- Create: `cluster-test/src/main/java/com/authx/cluster/benchmark/BatchBenchmark.java`
- Create: `cluster-test/src/main/java/com/authx/cluster/benchmark/FaultRecoveryBenchmark.java`

**Steps:**

1. Create `DeepInheritanceBenchmark.java` — B4:
   - Finds folder chains of depth 20 (the generator creates these).
   - Checks permission on the deepest document by a user who has access only at the root folder.
   - Measures check latency. This exercises ancestor parallel dispatch.

2. Create `BatchBenchmark.java` — B5:
   - Each op: builds a batch of 100 grant operations via `client.on("document").resource(docId).batch()`, executes.
   - Then a matching batch of 100 revoke operations.
   - Tracks batch TPS and per-op latency.

3. Create `FaultRecoveryBenchmark.java` — B6:
   - Starts a background check loop (continuous reads).
   - Instructs the operator (via log message) to kill spicedb-2 (`docker compose stop spicedb-2`).
   - Monitors error rate spike and recovery.
   - After a configured wait (30s), instructs to restart (`docker compose start spicedb-2`).
   - Reports: time to detect failure, error rate during outage, time to recover to 99%+ success.
   - Note: actual docker commands are manual — the benchmark only observes and reports.

4. Run `./gradlew :cluster-test:compileJava`.

---

### Task T009: CorrectnessVerifier

**Files:**
- Create: `cluster-test/src/main/java/com/authx/cluster/verify/CorrectnessVerifier.java`

**Steps:**

1. Create `CorrectnessVerifier.java` — 5 scenarios:

   - **deepInheritance():** Create a chain: folder-deep-1 → folder-deep-2 → ... → folder-deep-20 → document. Grant viewer at folder-deep-1. Check view on document. Assert true.
   - **departmentRecursion():** Pick a child department member. Check access to a space that grants access to the parent department via `department#all_members`. Assert true.
   - **linkSharing():** Find a document with `link_viewer@user:*`. Check view by a random user not otherwise authorized. Assert true.
   - **batchAtomicity():** Grant 10 relations in a batch. Verify all exist. Revoke all in a batch. Verify all gone.
   - **crossInstanceConsistency():** Write via this instance with full consistency. Immediately check on this instance with full consistency. Assert true.

   Each returns `VerifyResult(String scenario, boolean passed, String detail)`.
   The `runAll()` method returns `List<VerifyResult>`.

2. Run `./gradlew :cluster-test:compileJava`.

---

### Task T010: Prometheus config + Docker Compose update

**Files:**
- Create: `deploy/prometheus/prometheus.yml`
- Modify: `deploy/docker-compose.yml`

**Steps:**

1. Create `deploy/prometheus/prometheus.yml`:
```yaml
global:
  scrape_interval: 10s

scrape_configs:
  - job_name: spicedb
    static_configs:
      - targets:
          - "host.docker.internal:9090"
          - "host.docker.internal:9091"
        labels:
          cluster: spicedb

  - job_name: cockroachdb
    metrics_path: /_status/vars
    static_configs:
      - targets:
          - "host.docker.internal:8080"
          - "host.docker.internal:8081"
          - "host.docker.internal:8082"
        labels:
          cluster: cockroachdb

  - job_name: cluster-test
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - "host.docker.internal:8091"
          - "host.docker.internal:8092"
          - "host.docker.internal:8093"
        labels:
          cluster: app
```

Note: Uses `host.docker.internal` because SpiceDB, CockroachDB, and Spring Boot apps all run on the host (via `cluster-up.sh` or local `bootRun`), while Prometheus/Grafana run in Docker. On Linux, may need `--add-host=host.docker.internal:host-gateway` (added in docker-compose).

2. Add to `deploy/docker-compose.yml` — two new services:
```yaml
  prometheus:
    image: prom/prometheus:v3.2.1
    ports:
      - "9092:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    extra_hosts:
      - "host.docker.internal:host-gateway"

  grafana:
    image: grafana/grafana:11.6.0
    ports:
      - "3000:3000"
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: "Admin"
      GF_SECURITY_ADMIN_PASSWORD: "admin"
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
    depends_on:
      - prometheus
```

3. Verify: `docker compose config` succeeds.

---

### Task T011: Grafana provisioning + 3 dashboards

**Files:**
- Create: `deploy/grafana/provisioning/datasources/prometheus.yml`
- Create: `deploy/grafana/provisioning/dashboards/dashboard.yml`
- Create: `deploy/grafana/dashboards/spicedb.json`
- Create: `deploy/grafana/dashboards/cockroachdb.json`
- Create: `deploy/grafana/dashboards/sdk-app.json`

**Steps:**

1. Create datasource provisioning `deploy/grafana/provisioning/datasources/prometheus.yml`:
```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```

2. Create dashboard provisioning `deploy/grafana/provisioning/dashboards/dashboard.yml`:
```yaml
apiVersion: 1
providers:
  - name: Default
    folder: ""
    type: file
    options:
      path: /var/lib/grafana/dashboards
```

3. Create `spicedb.json` — panels:
   - gRPC request rate (`grpc_server_handled_total` by method)
   - gRPC latency p50/p99 (`grpc_server_handling_seconds`)
   - Dispatch cache hit/miss rate
   - Namespace cache hit/miss rate
   - Active streams

4. Create `cockroachdb.json` — panels:
   - SQL query rate and latency
   - Range count and distribution
   - LSM compaction
   - Replica health (under-replicated ranges)
   - Node liveness

5. Create `sdk-app.json` — panels:
   - SDK check TPS (by node)
   - SDK check p50/p99 latency
   - Cache hit rate
   - Error rate
   - JVM memory / GC

---

### Task T012: Start/stop scripts

**Files:**
- Create: `cluster-test/scripts/start-cluster.sh`
- Create: `cluster-test/scripts/stop-cluster.sh`

**Steps:**

1. Create `start-cluster.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

echo "Starting 3 cluster-test instances..."

NODE_INDEX=0 SERVER_PORT=8091 ./gradlew :cluster-test:bootRun --quiet &
PID1=$!
NODE_INDEX=1 SERVER_PORT=8092 ./gradlew :cluster-test:bootRun --quiet &
PID2=$!
NODE_INDEX=2 SERVER_PORT=8093 ./gradlew :cluster-test:bootRun --quiet &
PID3=$!

echo "PIDs: $PID1, $PID2, $PID3"
echo "$PID1 $PID2 $PID3" > /tmp/cluster-test-pids.txt

echo "Waiting for instances to be ready..."
for port in 8091 8092 8093; do
    for i in $(seq 1 30); do
        curl -sf "http://localhost:$port/actuator/health" > /dev/null 2>&1 && break
        sleep 2
    done
    echo "  :$port ready"
done
echo "All 3 instances running."
```

2. Create `stop-cluster.sh`:
```bash
#!/usr/bin/env bash
echo "Stopping cluster-test instances..."
if [ -f /tmp/cluster-test-pids.txt ]; then
    kill $(cat /tmp/cluster-test-pids.txt) 2>/dev/null || true
    rm /tmp/cluster-test-pids.txt
fi
# Fallback: kill any bootRun processes for cluster-test
pkill -f 'cluster-test.*bootRun' 2>/dev/null || true
echo "Stopped."
```

3. `chmod +x cluster-test/scripts/*.sh`

---

### Task T013: Compile + smoke test

**Steps:**

1. Run `./gradlew :cluster-test:compileJava` — must pass.
2. Verify `settings.gradle` includes `cluster-test`.
3. Verify file count:
   - `cluster-test/src/main/java/` — 14 Java files
   - `deploy/prometheus/` — 1 file
   - `deploy/grafana/` — 5 files
4. Run `docker compose -f deploy/docker-compose.yml config` — must succeed.
