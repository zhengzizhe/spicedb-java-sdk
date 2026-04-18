# Cluster Stress Test Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a `cluster-test` Spring Boot multi-instance harness with 8 correctness tests, 5 baseline benchmarks, 7 P0/P1 resilience validations (toxiproxy-driven), 2 stress tests, 1 soak test, and a self-contained HTML report.

**Architecture:** New Gradle submodule `cluster-test/`, three identical Spring Boot instances differentiated by `NODE_INDEX` env var, REST-driven test execution coordinated by bash orchestrator scripts. Failure injection uses Toxiproxy (network) + docker (container). Results aggregate to JSON, then a Java HTML generator builds `report.html` with embedded Chart.js for charts.

**Tech Stack:** Java 21, Spring Boot 3.4, Micrometer, HdrHistogram, Toxiproxy Java client, Docker Compose, bash + curl + jq.

---

## File Structure

```
cluster-test/
├── build.gradle                                # Gradle module config
├── src/main/java/com/authx/clustertest/
│   ├── ClusterTestApp.java                     # @SpringBootApplication entry
│   ├── config/
│   │   ├── ClusterProps.java                   # @ConfigurationProperties
│   │   ├── SdkConfig.java                      # AuthxClient bean
│   │   └── ResultsRepo.java                    # writes JSON to results-dir
│   ├── data/
│   │   ├── DataModel.java                      # entity counts + relation counts
│   │   ├── RelationshipFileGenerator.java      # streaming writer
│   │   └── BulkImporter.java                   # direct gRPC BulkImportRelationships
│   ├── api/
│   │   ├── DataController.java                 # /test/data/*
│   │   ├── CorrectnessController.java          # /test/correctness/*
│   │   ├── BenchmarkController.java            # /test/bench/*
│   │   ├── ResilienceController.java           # /test/resilience/*
│   │   ├── StressController.java               # /test/stress/*
│   │   ├── SoakController.java                 # /test/soak/*
│   │   ├── ReportController.java               # /test/report/generate
│   │   └── ResultsController.java              # /test/results/*
│   ├── benchmark/
│   │   ├── ScenarioRunner.java                 # threads + HdrHistogram + duration
│   │   ├── BenchmarkResult.java                # JSON record
│   │   ├── B1ReadBenchmark.java
│   │   ├── B2WriteBenchmark.java
│   │   ├── B3ConsistencyBenchmark.java
│   │   ├── B4DeepInheritanceBenchmark.java
│   │   └── B5BatchBenchmark.java
│   ├── correctness/
│   │   ├── CorrectnessRunner.java              # runs C1-C8, returns aggregated JSON
│   │   ├── CorrectnessResult.java
│   │   ├── C1DirectGrant.java
│   │   ├── C2Revoke.java
│   │   ├── C3GroupInheritance.java
│   │   ├── C4DeepFolder.java
│   │   ├── C5Caveat.java
│   │   ├── C6Expiration.java
│   │   ├── C7CrossInstance.java
│   │   └── C8BatchAtomic.java
│   ├── resilience/
│   │   ├── ResilienceResult.java
│   │   ├── R1StreamLeakTest.java
│   │   ├── R2CursorExpiredTest.java
│   │   ├── R3StreamStaleTest.java
│   │   ├── R4TokenStoreTest.java
│   │   ├── R5DoubleDeleteTest.java
│   │   ├── R6BreakerEvictionTest.java
│   │   └── R7CloseRobustnessTest.java
│   ├── stress/
│   │   ├── S1RampTest.java
│   │   └── S2SustainedTest.java
│   ├── soak/
│   │   ├── L1SoakTest.java
│   │   └── ResourceSampler.java                # samples heap/threads/cache/etc
│   └── report/
│       ├── HtmlReportGenerator.java
│       ├── ChartDataBuilder.java               # builds Chart.js data structures
│       └── EnvironmentInfo.java                # JDK/Docker/image versions
├── src/main/resources/
│   ├── application.yml
│   └── web/chart.min.js                        # vendored Chart.js v4
├── orchestrator/
│   ├── run-all.sh                              # main entry
│   ├── start-cluster.sh
│   ├── stop-cluster.sh
│   ├── run-correctness.sh
│   ├── run-baseline.sh
│   ├── run-resilience.sh
│   ├── run-stress.sh
│   ├── run-soak.sh
│   └── inject/
│       ├── stall-watch.sh
│       ├── restore-watch.sh
│       ├── kill-spicedb.sh
│       └── restore-spicedb.sh
├── deploy/                                     # docker-compose extension
│   ├── docker-compose.cluster-test.yml         # adds toxiproxy + 3 spring boot
│   └── toxiproxy-init.sh                       # configures proxies on startup
└── results/                                    # output (gitignored)
    ├── correctness.json
    ├── baseline.json
    ├── resilience.json
    ├── stress.json
    ├── soak.json
    └── report.html
```

---

## Task T001: Create cluster-test module skeleton

**Files:**
- Create: `settings.gradle`, `cluster-test/build.gradle`, `cluster-test/.gitignore`

**Steps:**

1. Append to `settings.gradle`:

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
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("com.authzed.api:authzed:1.5.4")
    implementation("io.grpc:grpc-netty-shaded:1.80.0")
    implementation("io.grpc:grpc-stub:1.80.0")
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
    implementation("eu.rekawek.toxiproxy:toxiproxy-java:2.1.7")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

test { useJUnitPlatform() }
```

3. Create `cluster-test/.gitignore`:

```
results/
*.log
build/
```

4. Verify build:

```bash
./gradlew :cluster-test:compileJava
```

Expected: `BUILD SUCCESSFUL`.

5. Commit:

```bash
git add settings.gradle cluster-test/
git commit -m "feat(cluster-test): scaffold Gradle module"
```

---

## Task T002: Spring Boot app + config + properties

**Files:**
- Create: `cluster-test/src/main/java/com/authx/clustertest/ClusterTestApp.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/config/ClusterProps.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/config/SdkConfig.java`
- Create: `cluster-test/src/main/resources/application.yml`

**Steps:**

1. `ClusterTestApp.java`:

```java
package com.authx.clustertest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClusterTestApp {
    public static void main(String[] args) {
        SpringApplication.run(ClusterTestApp.class, args);
    }
}
```

2. `config/ClusterProps.java`:

```java
package com.authx.clustertest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cluster")
public record ClusterProps(
        int nodeIndex,
        int nodeCount,
        int leaderPort,
        String resultsDir,
        SpiceDb spicedb,
        Toxiproxy toxiproxy
) {
    public boolean isLeader() { return nodeIndex == 0; }
    public record SpiceDb(String targets, String presharedKey) {}
    public record Toxiproxy(String host, int port, boolean enabled) {}
}
```

3. `config/SdkConfig.java`:

```java
package com.authx.clustertest.config;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.policy.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class SdkConfig {

    @Bean(destroyMethod = "close")
    public AuthxClient authxClient(ClusterProps props) {
        var addrs = props.spicedb().targets().split(",");
        return AuthxClient.builder()
                .connection(c -> c
                        .targets(addrs)
                        .presharedKey(props.spicedb().presharedKey())
                        .requestTimeout(Duration.ofSeconds(30)))
                .cache(c -> c
                        .enabled(true)
                        .maxSize(200_000)
                        .watchInvalidation(true))
                .features(f -> f
                        .virtualThreads(true)
                        .telemetry(true)
                        .shutdownHook(false))   // orchestrator manages lifecycle
                .extend(e -> e.policies(PolicyRegistry.builder()
                        .defaultPolicy(ResourcePolicy.builder()
                                .cache(CachePolicy.of(Duration.ofSeconds(30)))
                                .readConsistency(ReadConsistency.session())
                                .build())
                        .build()))
                .build();
    }
}
```

4. `application.yml`:

```yaml
server:
  port: ${SERVER_PORT:8091}

spring:
  application:
    name: cluster-test-${cluster.node-index}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    export:
      prometheus:
        enabled: true

cluster:
  node-index: ${NODE_INDEX:0}
  node-count: 3
  leader-port: 8091
  results-dir: ${RESULTS_DIR:./results}
  spicedb:
    targets: ${SPICEDB_TARGETS:localhost:50051,localhost:50052,localhost:50053}
    preshared-key: testkey
  toxiproxy:
    host: ${TOXIPROXY_HOST:localhost}
    port: ${TOXIPROXY_PORT:8474}
    enabled: ${TOXIPROXY_ENABLED:false}

logging:
  level:
    com.authx: INFO
```

5. Verify boots:

```bash
NODE_INDEX=0 ./gradlew :cluster-test:bootRun &
PID=$!
sleep 15
curl -s http://localhost:8091/actuator/health | grep -q '"status":"UP"' && echo OK
kill $PID
```

6. Commit:

```bash
git add cluster-test/
git commit -m "feat(cluster-test): Spring Boot app + AuthxClient bean + config"
```

---

## Task T003: ResultsRepo for JSON output

**Files:**
- Create: `cluster-test/src/main/java/com/authx/clustertest/config/ResultsRepo.java`

**Steps:**

1. Create `ResultsRepo.java`:

```java
package com.authx.clustertest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class ResultsRepo {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path baseDir;

    public ResultsRepo(ClusterProps props) {
        this.baseDir = Paths.get(props.resultsDir(), "instance-" + props.nodeIndex());
        try { Files.createDirectories(baseDir); } catch (IOException e) { throw new RuntimeException(e); }
    }

    public void write(String name, Object data) {
        try {
            mapper.writeValue(baseDir.resolve(name + ".json").toFile(), data);
        } catch (IOException e) {
            throw new RuntimeException("write " + name + " failed", e);
        }
    }

    public <T> T read(String name, Class<T> type) {
        try {
            var f = baseDir.resolve(name + ".json").toFile();
            return f.exists() ? mapper.readValue(f, type) : null;
        } catch (IOException e) {
            throw new RuntimeException("read " + name + " failed", e);
        }
    }

    public Path baseDir() { return baseDir; }
}
```

2. Commit:

```bash
git add cluster-test/src/main/java/com/authx/clustertest/config/ResultsRepo.java
git commit -m "feat(cluster-test): ResultsRepo for per-instance JSON output"
```

---

## Task T004: Docker Compose extension + Toxiproxy

**Files:**
- Create: `cluster-test/deploy/docker-compose.cluster-test.yml`
- Create: `cluster-test/deploy/toxiproxy-init.sh`

**Steps:**

1. `docker-compose.cluster-test.yml` — add to existing cluster:

```yaml
# Used WITH deploy/docker-compose.yml via:
#   docker compose -f deploy/docker-compose.yml -f cluster-test/deploy/docker-compose.cluster-test.yml up
services:
  toxiproxy:
    image: ghcr.io/shopify/toxiproxy:2.9.0
    ports:
      - "8474:8474"   # control API
      - "50161:50161" # proxy → spicedb-1:50051
      - "50162:50162" # proxy → spicedb-2:50051
      - "50163:50163" # proxy → spicedb-3:50051
    networks: [default]

  toxiproxy-init:
    image: ghcr.io/shopify/toxiproxy:2.9.0
    entrypoint: ["/init/init.sh"]
    volumes:
      - ./toxiproxy-init.sh:/init/init.sh:ro
    depends_on:
      - toxiproxy
    networks: [default]
```

2. `toxiproxy-init.sh`:

```bash
#!/bin/sh
set -e
# Wait for toxiproxy
for i in $(seq 1 30); do
  /toxiproxy-cli --host=toxiproxy:8474 list >/dev/null 2>&1 && break
  sleep 1
done
# Create proxies for the three SpiceDB instances
/toxiproxy-cli --host=toxiproxy:8474 create -l 0.0.0.0:50161 -u spicedb-1:50051 spicedb-1
/toxiproxy-cli --host=toxiproxy:8474 create -l 0.0.0.0:50162 -u spicedb-2:50051 spicedb-2
/toxiproxy-cli --host=toxiproxy:8474 create -l 0.0.0.0:50163 -u spicedb-3:50051 spicedb-3
echo "toxiproxy proxies configured"
```

3. Make executable:

```bash
chmod +x cluster-test/deploy/toxiproxy-init.sh
```

4. Smoke test:

```bash
docker compose -f deploy/docker-compose.yml -f cluster-test/deploy/docker-compose.cluster-test.yml up -d toxiproxy toxiproxy-init
sleep 5
curl -s http://localhost:8474/proxies | grep -q spicedb-1 && echo OK
docker compose -f deploy/docker-compose.yml -f cluster-test/deploy/docker-compose.cluster-test.yml down -v
```

5. Commit:

```bash
git add cluster-test/deploy/
git commit -m "feat(cluster-test): docker-compose + toxiproxy extension"
```

---

## Task T005: Data generator + bulk importer

**Files:**
- Create: `cluster-test/src/main/java/com/authx/clustertest/data/DataModel.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/data/RelationshipFileGenerator.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/data/BulkImporter.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/api/DataController.java`

**Steps:**

1. `DataModel.java` — counts only:

```java
package com.authx.clustertest.data;

public record DataModel(
        int users, int groups, int organizations, int spaces,
        int folders, int folderMaxDepth, int documents,
        long expectedRelationships
) {
    public static DataModel defaults() {
        return new DataModel(10_000, 200, 10, 1_000, 50_000, 20, 500_000, 10_000_000L);
    }
}
```

2. `RelationshipFileGenerator.java`:

```java
package com.authx.clustertest.data;

import com.authzed.api.v1.*;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RelationshipFileGenerator {
    private static final Logger log = LoggerFactory.getLogger(RelationshipFileGenerator.class);
    private static final long SEED = 42L;

    /** Writes one relation per line: {resourceType:id#relation@subjectType:subjectId} */
    public long generate(Path output, DataModel m) throws IOException {
        Files.createDirectories(output.getParent());
        var rng = new Random(SEED);
        var count = new AtomicLong();
        try (BufferedWriter w = Files.newBufferedWriter(output)) {
            generateOrgs(w, m, rng, count);
            generateGroups(w, m, rng, count);
            generateSpaces(w, m, rng, count);
            generateFolders(w, m, rng, count);
            generateDocuments(w, m, rng, count);
        }
        log.info("Generated {} relationships to {}", count.get(), output);
        return count.get();
    }

    private void writeRel(BufferedWriter w, String rt, String rid, String rel, String st, String sid) throws IOException {
        w.write(rt); w.write(":"); w.write(rid); w.write("#"); w.write(rel);
        w.write("@"); w.write(st); w.write(":"); w.write(sid); w.write("\n");
    }

    private void generateOrgs(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        for (int i = 0; i < m.organizations(); i++) {
            // 1000 members per org, 10 admins
            for (int j = 0; j < 1000; j++) {
                writeRel(w, "organization", "org-" + i, "member", "user", "user-" + rng.nextInt(m.users()));
                c.incrementAndGet();
            }
            for (int j = 0; j < 10; j++) {
                writeRel(w, "organization", "org-" + i, "admin", "user", "user-" + rng.nextInt(m.users()));
                c.incrementAndGet();
            }
        }
    }

    private void generateGroups(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        // Each group: 20 members
        for (int i = 0; i < m.groups(); i++) {
            for (int j = 0; j < 20; j++) {
                writeRel(w, "group", "grp-" + i, "member", "user", "user-" + rng.nextInt(m.users()));
                c.incrementAndGet();
            }
        }
    }

    private void generateSpaces(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        // Each space: 25 members + 4 viewers + 1 admin
        for (int i = 0; i < m.spaces(); i++) {
            for (int j = 0; j < 25; j++) {
                writeRel(w, "space", "spc-" + i, "member", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            }
            for (int j = 0; j < 4; j++) {
                writeRel(w, "space", "spc-" + i, "viewer", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            }
            writeRel(w, "space", "spc-" + i, "admin", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
        }
    }

    private void generateFolders(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        // Build folder tree with depth up to folderMaxDepth, and emit ancestor flattening
        // Simplification: generate as shallow chain segments — for each folder, pick a random parent
        // among already-generated folders, capped at depth = folderMaxDepth.
        int[] depth = new int[m.folders()];
        for (int i = 0; i < m.folders(); i++) {
            int parentIdx = i == 0 ? -1 : rng.nextInt(i);
            int d = parentIdx == -1 ? 0 : Math.min(depth[parentIdx] + 1, m.folderMaxDepth());
            depth[i] = d;
            // 4 viewers + 1 editor per folder
            for (int j = 0; j < 4; j++) {
                writeRel(w, "folder", "fld-" + i, "viewer", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            }
            writeRel(w, "folder", "fld-" + i, "editor", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            // ancestor flattening: walk up from parent and emit ancestor relations
            int cur = parentIdx;
            while (cur != -1) {
                writeRel(w, "folder", "fld-" + i, "ancestor", "folder", "fld-" + cur); c.incrementAndGet();
                cur = cur == 0 ? -1 : rng.nextInt(cur);
                // bound to keep total relations manageable
                if (rng.nextInt(100) < 30) break;
            }
        }
    }

    private void generateDocuments(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        for (int i = 0; i < m.documents(); i++) {
            // each doc: 1 folder, 1 space, 8 viewers, 2 editors, 1 owner, 0.5 link_viewer
            writeRel(w, "document", "doc-" + i, "folder", "folder", "fld-" + rng.nextInt(m.folders())); c.incrementAndGet();
            writeRel(w, "document", "doc-" + i, "space", "space", "spc-" + rng.nextInt(m.spaces())); c.incrementAndGet();
            for (int j = 0; j < 8; j++) {
                writeRel(w, "document", "doc-" + i, "viewer", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            }
            for (int j = 0; j < 2; j++) {
                writeRel(w, "document", "doc-" + i, "editor", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            }
            writeRel(w, "document", "doc-" + i, "owner", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            if (rng.nextInt(2) == 0) {
                writeRel(w, "document", "doc-" + i, "link_viewer", "user", "*"); c.incrementAndGet();
            }
        }
    }
}
```

3. `BulkImporter.java`:

```java
package com.authx.clustertest.data;

import com.authx.clustertest.config.ClusterProps;
import com.authzed.api.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;

@Component
public class BulkImporter {
    private static final Logger log = LoggerFactory.getLogger(BulkImporter.class);
    private static final int BATCH = 1000;
    // Pattern: type:id#rel@stype:sid  (sid may be * for wildcard)
    private static final Pattern LINE = Pattern.compile("^([^:]+):([^#]+)#([^@]+)@([^:]+):(.+)$");

    private final ClusterProps props;
    public BulkImporter(ClusterProps props) { this.props = props; }

    public long importFile(Path file) throws Exception {
        var addr = props.spicedb().targets().split(",")[0]; // pick first
        ManagedChannel ch = ManagedChannelBuilder.forTarget(addr).usePlaintext().maxInboundMessageSize(64 * 1024 * 1024).build();
        try {
            var meta = new Metadata();
            meta.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + props.spicedb().presharedKey());
            var stub = ExperimentalServiceGrpc.newStub(ch).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(meta));

            var done = new CountDownLatch(1);
            var counted = new AtomicLong();
            StreamObserver<BulkImportRelationshipsResponse> resp = new StreamObserver<>() {
                public void onNext(BulkImportRelationshipsResponse r) { counted.addAndGet(r.getNumLoaded()); }
                public void onError(Throwable t) { log.error("Bulk import failed", t); done.countDown(); }
                public void onCompleted() { done.countDown(); }
            };
            StreamObserver<BulkImportRelationshipsRequest> req = stub.bulkImportRelationships(resp);

            var batch = new ArrayList<Relationship>(BATCH);
            try (var lines = Files.lines(file)) {
                lines.forEach(line -> {
                    var rel = parse(line);
                    if (rel != null) batch.add(rel);
                    if (batch.size() >= BATCH) {
                        req.onNext(BulkImportRelationshipsRequest.newBuilder().addAllRelationships(batch).build());
                        batch.clear();
                    }
                });
            }
            if (!batch.isEmpty()) {
                req.onNext(BulkImportRelationshipsRequest.newBuilder().addAllRelationships(batch).build());
            }
            req.onCompleted();
            done.await(10, TimeUnit.MINUTES);
            return counted.get();
        } finally {
            ch.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private Relationship parse(String line) {
        var m = LINE.matcher(line.trim());
        if (!m.matches()) return null;
        var subBuilder = SubjectReference.newBuilder()
                .setObject(ObjectReference.newBuilder()
                        .setObjectType(m.group(4))
                        .setObjectId(m.group(5)));
        return Relationship.newBuilder()
                .setResource(ObjectReference.newBuilder().setObjectType(m.group(1)).setObjectId(m.group(2)))
                .setRelation(m.group(3))
                .setSubject(subBuilder)
                .build();
    }
}
```

4. `api/DataController.java`:

```java
package com.authx.clustertest.api;

import com.authx.clustertest.config.ClusterProps;
import com.authx.clustertest.data.*;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RestController
@RequestMapping("/test/data")
public class DataController {
    private final RelationshipFileGenerator gen;
    private final BulkImporter importer;
    private final ClusterProps props;
    private volatile long generated, imported;

    public DataController(RelationshipFileGenerator g, BulkImporter i, ClusterProps p) {
        this.gen = g; this.importer = i; this.props = p;
    }

    @PostMapping("/generate")
    public Map<String, Object> generate() throws Exception {
        if (!props.isLeader()) return Map.of("status", "skipped", "reason", "non-leader");
        var path = Paths.get(props.resultsDir(), "relations.txt");
        generated = gen.generate(path, DataModel.defaults());
        return Map.of("status", "ok", "count", generated, "file", path.toString());
    }

    @PostMapping("/import")
    public Map<String, Object> importData() throws Exception {
        if (!props.isLeader()) return Map.of("status", "skipped", "reason", "non-leader");
        var path = Paths.get(props.resultsDir(), "relations.txt");
        long t0 = System.currentTimeMillis();
        imported = importer.importFile(path);
        return Map.of("status", "ok", "count", imported, "elapsedMs", System.currentTimeMillis() - t0);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("generated", generated, "imported", imported);
    }
}
```

5. Verify compiles:

```bash
./gradlew :cluster-test:compileJava
```

6. Commit:

```bash
git add cluster-test/src/main/java/com/authx/clustertest/data/ cluster-test/src/main/java/com/authx/clustertest/api/DataController.java
git commit -m "feat(cluster-test): data generation + bulk import via gRPC"
```

---

## Task T006: Correctness tests C1-C8

**Files:**
- Create: `cluster-test/src/main/java/com/authx/clustertest/correctness/CorrectnessResult.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/correctness/CorrectnessRunner.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/correctness/C1DirectGrant.java` ... `C8BatchAtomic.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/api/CorrectnessController.java`

**Steps:**

1. `CorrectnessResult.java`:

```java
package com.authx.clustertest.correctness;

public record CorrectnessResult(String name, String status, long durationMs, String details) {
    public static CorrectnessResult pass(String name, long ms) { return new CorrectnessResult(name, "PASS", ms, ""); }
    public static CorrectnessResult fail(String name, long ms, String why) { return new CorrectnessResult(name, "FAIL", ms, why); }
}
```

2. `CorrectnessRunner.java`:

```java
package com.authx.clustertest.correctness;

import com.authx.sdk.AuthxClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

@Component
public class CorrectnessRunner {
    private final AuthxClient client;
    public CorrectnessRunner(AuthxClient c) { this.client = c; }

    public List<CorrectnessResult> runAll() {
        return List.of(
                run("C1-DirectGrant",      new C1DirectGrant()),
                run("C2-Revoke",           new C2Revoke()),
                run("C3-GroupInheritance", new C3GroupInheritance()),
                run("C4-DeepFolder",       new C4DeepFolder()),
                run("C5-Caveat",           new C5Caveat()),
                run("C6-Expiration",       new C6Expiration()),
                run("C7-CrossInstance",    new C7CrossInstance()),
                run("C8-BatchAtomic",      new C8BatchAtomic())
        );
    }

    private CorrectnessResult run(String name, Function<AuthxClient, String> test) {
        long t0 = System.currentTimeMillis();
        try {
            String why = test.apply(client);
            return why == null
                    ? CorrectnessResult.pass(name, System.currentTimeMillis() - t0)
                    : CorrectnessResult.fail(name, System.currentTimeMillis() - t0, why);
        } catch (Exception e) {
            return CorrectnessResult.fail(name, System.currentTimeMillis() - t0, e.toString());
        }
    }
}
```

3. Each `Cn*.java` is a `Function<AuthxClient, String>` returning `null` on pass or a reason string on fail. Example `C1DirectGrant.java`:

```java
package com.authx.clustertest.correctness;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;

import java.util.UUID;
import java.util.function.Function;

public class C1DirectGrant implements Function<AuthxClient, String> {
    @Override public String apply(AuthxClient client) {
        String docId = "c1-doc-" + UUID.randomUUID();
        String userId = "c1-user-" + UUID.randomUUID();
        client.on("document").select(docId).grant("editor").to("user", userId);
        boolean ok = client.on("document").select(docId).check("edit").withConsistency(Consistency.full()).by("user", userId);
        return ok ? null : "expected edit=true after grant editor";
    }
}
```

Implement `C2..C8` analogously per spec req-4. Each file ≤30 lines of focused logic.

4. `api/CorrectnessController.java`:

```java
package com.authx.clustertest.api;

import com.authx.clustertest.config.ResultsRepo;
import com.authx.clustertest.correctness.CorrectnessRunner;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/test/correctness")
public class CorrectnessController {
    private final CorrectnessRunner runner;
    private final ResultsRepo repo;

    public CorrectnessController(CorrectnessRunner r, ResultsRepo s) { this.runner = r; this.repo = s; }

    @PostMapping("/run-all")
    public Map<String, Object> runAll() {
        var results = runner.runAll();
        long passes = results.stream().filter(r -> r.status().equals("PASS")).count();
        var summary = Map.of("total", results.size(), "passed", passes, "results", results);
        repo.write("correctness", summary);
        return summary;
    }
}
```

5. Compile and commit:

```bash
./gradlew :cluster-test:compileJava
git add cluster-test/src/main/java/com/authx/clustertest/correctness/ \
        cluster-test/src/main/java/com/authx/clustertest/api/CorrectnessController.java
git commit -m "feat(cluster-test): C1-C8 correctness tests + REST endpoint"
```

---

## Task T007: Benchmark engine + B1-B5

**Files:**
- Create: `cluster-test/src/main/java/com/authx/clustertest/benchmark/BenchmarkResult.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/benchmark/ScenarioRunner.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/benchmark/B1ReadBenchmark.java` ... `B5BatchBenchmark.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/api/BenchmarkController.java`

**Steps:**

1. `BenchmarkResult.java`:

```java
package com.authx.clustertest.benchmark;

import java.util.Map;

public record BenchmarkResult(
        String scenario, int threads, long durationMs, long ops, double tps,
        long p50us, long p90us, long p99us, long p999us, long maxUs,
        long errors, Map<String, Long> errorsByType
) {}
```

2. `ScenarioRunner.java`:

```java
package com.authx.clustertest.benchmark;

import org.HdrHistogram.Histogram;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class ScenarioRunner {

    public BenchmarkResult run(String name, int threads, long durationMs, Consumer<Random> op) throws InterruptedException {
        var hist = new Histogram(60_000_000_000L, 3); // up to 60s, 3 sig figs
        var ops = new AtomicLong();
        var errors = new AtomicLong();
        var errorsByType = new ConcurrentHashMap<String, Long>();
        var pool = Executors.newFixedThreadPool(threads);
        long deadline = System.nanoTime() + durationMs * 1_000_000L;

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                var rng = new Random(Thread.currentThread().getId());
                while (System.nanoTime() < deadline) {
                    long t0 = System.nanoTime();
                    try {
                        op.accept(rng);
                        long us = (System.nanoTime() - t0) / 1000;
                        synchronized (hist) { hist.recordValue(Math.min(us, 60_000_000)); }
                        ops.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        errorsByType.merge(e.getClass().getSimpleName(), 1L, Long::sum);
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(durationMs + 30_000, TimeUnit.MILLISECONDS);

        return new BenchmarkResult(
                name, threads, durationMs, ops.get(),
                ops.get() * 1000.0 / durationMs,
                hist.getValueAtPercentile(50), hist.getValueAtPercentile(90),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getMaxValue(), errors.get(), Map.copyOf(errorsByType));
    }
}
```

3. Each `BnXxxBenchmark.java` exposes `run(int threads, long durationMs)`. Example `B1ReadBenchmark.java`:

```java
package com.authx.clustertest.benchmark;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class B1ReadBenchmark {
    private final AuthxClient client;
    public B1ReadBenchmark(AuthxClient c) { this.client = c; }

    public BenchmarkResult run(int threads, long durationMs) throws InterruptedException {
        return new ScenarioRunner().run("B1-Read", threads, durationMs, rng -> {
            int kind = rng.nextInt(100);
            String docId = "doc-" + rng.nextInt(500_000);
            String userId = "user-" + rng.nextInt(10_000);
            if (kind < 70) {
                client.on("document").select(docId).check("view").withConsistency(Consistency.minimizeLatency()).by("user", userId);
            } else if (kind < 80) {
                client.on("document").select(docId).checkAll().by("user", userId);
            } else if (kind < 90) {
                client.on("document").select(docId).who("view").limit(50).asUserIds();
            } else if (kind < 95) {
                client.on("document").findByUser(userId).limit(50).can("view");
            } else {
                client.on("document").select(docId).relations().fetch();
            }
        });
    }
}
```

Implement `B2-B5` analogously per spec req-5 (B2 = grant/revoke mix, B3 = write-then-read with `Strong`, B4 = check on doc whose folder is depth ≥15, B5 = batch grant of 100 items).

4. `api/BenchmarkController.java`:

```java
package com.authx.clustertest.api;

import com.authx.clustertest.benchmark.*;
import com.authx.clustertest.config.ResultsRepo;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/test/bench")
public class BenchmarkController {
    private final B1ReadBenchmark b1; private final B2WriteBenchmark b2;
    private final B3ConsistencyBenchmark b3; private final B4DeepInheritanceBenchmark b4;
    private final B5BatchBenchmark b5; private final ResultsRepo repo;

    public BenchmarkController(B1ReadBenchmark b1, B2WriteBenchmark b2, B3ConsistencyBenchmark b3,
                                B4DeepInheritanceBenchmark b4, B5BatchBenchmark b5, ResultsRepo r) {
        this.b1 = b1; this.b2 = b2; this.b3 = b3; this.b4 = b4; this.b5 = b5; this.repo = r;
    }

    @PostMapping("/{id}")
    public BenchmarkResult run(@PathVariable String id,
                                @RequestParam(defaultValue = "100") int threads,
                                @RequestParam(defaultValue = "60") int duration) throws Exception {
        long ms = duration * 1000L;
        BenchmarkResult r = switch (id) {
            case "B1" -> b1.run(threads, ms);
            case "B2" -> b2.run(threads, ms);
            case "B3" -> b3.run(threads, ms);
            case "B4" -> b4.run(threads, ms);
            case "B5" -> b5.run(threads, ms);
            default -> throw new IllegalArgumentException("unknown scenario: " + id);
        };
        var existing = repo.read("baseline", Map.class);
        Map<String, Object> all = existing != null ? new HashMap<>(existing) : new HashMap<>();
        all.put(id, r);
        repo.write("baseline", all);
        return r;
    }
}
```

5. Compile and commit:

```bash
./gradlew :cluster-test:compileJava
git add cluster-test/src/main/java/com/authx/clustertest/benchmark/ \
        cluster-test/src/main/java/com/authx/clustertest/api/BenchmarkController.java
git commit -m "feat(cluster-test): B1-B5 baseline benchmarks + HdrHistogram runner"
```

---

## Task T008: Resilience tests R1-R7

**Files:**
- Create: `cluster-test/src/main/java/com/authx/clustertest/resilience/ResilienceResult.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/resilience/R1StreamLeakTest.java` ... `R7CloseRobustnessTest.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/api/ResilienceController.java`

**Steps:**

1. `ResilienceResult.java`:

```java
package com.authx.clustertest.resilience;

import java.util.List;
import java.util.Map;

public record ResilienceResult(
        String id, String status, long durationMs, String description,
        Map<String, Object> injection,
        Map<String, Object> observed,
        List<String> events,
        String reason
) {}
```

2. Each `RnXxxTest.java` has `ResilienceResult run()`. Example `R3StreamStaleTest.java`:

```java
package com.authx.clustertest.resilience;

import com.authx.clustertest.config.ClusterProps;
import com.authx.sdk.AuthxClient;
import com.authx.sdk.event.SdkTypedEvent;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class R3StreamStaleTest {
    private final AuthxClient client;
    private final ClusterProps props;

    public R3StreamStaleTest(AuthxClient c, ClusterProps p) { this.client = c; this.props = p; }

    public ResilienceResult run() throws Exception {
        long t0 = System.currentTimeMillis();
        var events = Collections.synchronizedList(new ArrayList<String>());
        var staleCount = new AtomicInteger();
        client.eventBus().subscribe(SdkTypedEvent.WatchStreamStale.class, e -> {
            staleCount.incrementAndGet();
            events.add("WatchStreamStale@" + e.timestamp());
        });

        var tp = new ToxiproxyClient(props.toxiproxy().host(), props.toxiproxy().port());
        // bandwidth=0 on the SpiceDB proxy → simulates app-layer stall
        var proxy = tp.getProxy("spicedb-1");
        var toxic = proxy.toxics().bandwidth("stall", ToxicDirection.DOWNSTREAM, 0);
        try {
            // Wait > stale threshold (60s) + slack
            Thread.sleep(90_000);
        } finally {
            toxic.remove();
        }

        boolean ok = staleCount.get() >= 1;
        return new ResilienceResult("R3", ok ? "PASS" : "FAIL", System.currentTimeMillis() - t0,
                "Watch app-layer stall detection",
                Map.of("toxic", "bandwidth=0", "durationSec", 90),
                Map.of("staleEvents", staleCount.get()),
                List.copyOf(events),
                ok ? null : "expected at least 1 WatchStreamStale event");
    }
}
```

Implement R1, R2, R4, R5, R6, R7 analogously per spec req-6 acceptance criteria.

3. `api/ResilienceController.java`:

```java
package com.authx.clustertest.api;

import com.authx.clustertest.config.ResultsRepo;
import com.authx.clustertest.resilience.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/test/resilience")
public class ResilienceController {
    private final R1StreamLeakTest r1; private final R2CursorExpiredTest r2;
    private final R3StreamStaleTest r3; private final R4TokenStoreTest r4;
    private final R5DoubleDeleteTest r5; private final R6BreakerEvictionTest r6;
    private final R7CloseRobustnessTest r7; private final ResultsRepo repo;

    public ResilienceController(R1StreamLeakTest r1, R2CursorExpiredTest r2, R3StreamStaleTest r3,
                                  R4TokenStoreTest r4, R5DoubleDeleteTest r5, R6BreakerEvictionTest r6,
                                  R7CloseRobustnessTest r7, ResultsRepo repo) {
        this.r1 = r1; this.r2 = r2; this.r3 = r3; this.r4 = r4;
        this.r5 = r5; this.r6 = r6; this.r7 = r7; this.repo = repo;
    }

    @PostMapping("/{id}")
    public ResilienceResult run(@PathVariable String id) throws Exception {
        ResilienceResult r = switch (id) {
            case "R1" -> r1.run(); case "R2" -> r2.run();
            case "R3" -> r3.run(); case "R4" -> r4.run();
            case "R5" -> r5.run(); case "R6" -> r6.run();
            case "R7" -> r7.run();
            default -> throw new IllegalArgumentException(id);
        };
        var existing = repo.read("resilience", Map.class);
        Map<String, Object> all = existing != null ? new HashMap<>(existing) : new HashMap<>();
        all.put(id, r);
        repo.write("resilience", all);
        return r;
    }
}
```

4. Compile and commit:

```bash
./gradlew :cluster-test:compileJava
git add cluster-test/src/main/java/com/authx/clustertest/resilience/ \
        cluster-test/src/main/java/com/authx/clustertest/api/ResilienceController.java
git commit -m "feat(cluster-test): R1-R7 resilience tests + toxiproxy integration"
```

---

## Task T009: Stress (S1-S2) + Soak (L1) tests

**Files:**
- Create: `cluster-test/src/main/java/com/authx/clustertest/stress/S1RampTest.java`, `S2SustainedTest.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/soak/{L1SoakTest,ResourceSampler}.java`
- Create: `cluster-test/src/main/java/com/authx/clustertest/api/{StressController,SoakController}.java`

**Steps:**

1. `S1RampTest.java`:

```java
package com.authx.clustertest.stress;

import com.authx.clustertest.benchmark.B1ReadBenchmark;
import com.authx.clustertest.benchmark.BenchmarkResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class S1RampTest {
    private final B1ReadBenchmark b1;
    private static final int[] RAMP = {10, 100, 500, 1000, 2000, 5000};

    public S1RampTest(B1ReadBenchmark b1) { this.b1 = b1; }

    public List<BenchmarkResult> run() throws InterruptedException {
        var results = new ArrayList<BenchmarkResult>();
        for (int t : RAMP) {
            var r = b1.run(t, 30_000);
            results.add(r);
            // Stop early if breakpoint reached
            if (r.p99us() > 1_000_000 || r.errors() > r.ops() * 0.05) break;
        }
        return results;
    }
}
```

2. `S2SustainedTest.java` runs at the highest concurrency from S1 that stayed under p99 < 500ms, for 5 minutes.

3. `L1SoakTest.java` + `ResourceSampler.java`: 30 minute fixed-TPS run sampling every 30s. Records list of `Sample(timestamp, heapMB, threads, cacheSize, hitRate, breakerOpenCount, watchReconnectCount)`.

4. Controllers analogous to BenchmarkController, write JSON to `stress.json` / `soak.json`.

5. Compile and commit:

```bash
./gradlew :cluster-test:compileJava
git add cluster-test/src/main/java/com/authx/clustertest/stress/ \
        cluster-test/src/main/java/com/authx/clustertest/soak/ \
        cluster-test/src/main/java/com/authx/clustertest/api/StressController.java \
        cluster-test/src/main/java/com/authx/clustertest/api/SoakController.java
git commit -m "feat(cluster-test): S1/S2 stress + L1 soak tests"
```

---

## Task T010: HTML report generator

**Files:**
- Create: `cluster-test/src/main/java/com/authx/clustertest/report/{HtmlReportGenerator,EnvironmentInfo,ChartDataBuilder}.java`
- Create: `cluster-test/src/main/resources/web/chart.min.js` (vendored Chart.js v4)
- Create: `cluster-test/src/main/java/com/authx/clustertest/api/ReportController.java`

**Steps:**

1. Download Chart.js v4 to `cluster-test/src/main/resources/web/chart.min.js`:

```bash
curl -sL https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js \
  -o cluster-test/src/main/resources/web/chart.min.js
```

2. `EnvironmentInfo.java`:

```java
package com.authx.clustertest.report;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EnvironmentInfo {
    public Map<String, Object> snapshot() {
        var p = System.getProperties();
        return Map.of(
                "javaVersion", p.getProperty("java.version"),
                "javaVendor", p.getProperty("java.vendor"),
                "os", p.getProperty("os.name") + " " + p.getProperty("os.version"),
                "arch", p.getProperty("os.arch"),
                "cores", Runtime.getRuntime().availableProcessors(),
                "maxHeapMB", Runtime.getRuntime().maxMemory() / 1024 / 1024,
                "generatedAt", java.time.Instant.now().toString()
        );
    }
}
```

3. `HtmlReportGenerator.java` builds a single self-contained HTML file:

```java
package com.authx.clustertest.report;

import com.authx.clustertest.config.ResultsRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

@Component
public class HtmlReportGenerator {
    private final ResultsRepo repo; private final EnvironmentInfo env;
    private final ObjectMapper mapper = new ObjectMapper();

    public HtmlReportGenerator(ResultsRepo r, EnvironmentInfo e) { this.repo = r; this.env = e; }

    public Path generate() throws IOException {
        String chartJs = new String(new ClassPathResource("web/chart.min.js").getInputStream().readAllBytes());
        var data = Map.of(
                "env", env.snapshot(),
                "correctness", repo.read("correctness", Map.class),
                "baseline", repo.read("baseline", Map.class),
                "resilience", repo.read("resilience", Map.class),
                "stress", repo.read("stress", Map.class),
                "soak", repo.read("soak", Map.class)
        );
        String json = mapper.writeValueAsString(data);
        String html = """
                <!doctype html><html><head><meta charset="utf-8"><title>AuthX Cluster Stress Report</title>
                <style>
                  body{font-family:-apple-system,Segoe UI,sans-serif;margin:24px;color:#222}
                  h1{border-bottom:2px solid #333} h2{margin-top:32px;color:#0a5}
                  table{border-collapse:collapse;margin:8px 0} td,th{border:1px solid #ccc;padding:6px 12px}
                  .pass{background:#d4f4dd} .fail{background:#fbd}
                  canvas{max-width:900px}
                  pre{background:#f4f4f4;padding:8px;overflow:auto;font-size:12px}
                </style>
                <script>%s</script></head><body>
                <h1>AuthX Cluster Stress Test Report</h1>
                <div id="root"></div>
                <script>const DATA=%s; renderReport(DATA);
                """.formatted(chartJs, json);
        // Append render.js content (renders DATA into #root)
        html += renderJs() + "</script></body></html>";
        Path out = repo.baseDir().resolveSibling("report.html");
        Files.writeString(out, html);
        return out;
    }

    private String renderJs() {
        // Minimal renderer — sections + tables + charts. Embedded as JS string.
        return """
                function renderReport(d){
                  const r=document.getElementById('root');
                  r.innerHTML += renderEnv(d.env);
                  r.innerHTML += renderSummary(d);
                  r.innerHTML += renderCorrectness(d.correctness);
                  r.innerHTML += renderBaseline(d.baseline);
                  r.innerHTML += renderResilience(d.resilience);
                  r.innerHTML += renderStress(d.stress);
                  r.innerHTML += renderSoak(d.soak);
                  drawCharts(d);
                }
                function renderEnv(e){return '<h2>Environment</h2><pre>'+JSON.stringify(e,null,2)+'</pre>';}
                function renderSummary(d){
                  const c=d.correctness||{}; const r=d.resilience||{};
                  return '<h2>Executive Summary</h2><table><tr><th>Category</th><th>Total</th><th>Passed</th></tr>'
                    + '<tr><td>Correctness</td><td>'+(c.total||0)+'</td><td>'+(c.passed||0)+'</td></tr>'
                    + '<tr><td>Resilience</td><td>'+Object.keys(r).length+'</td><td>'+Object.values(r).filter(x=>x.status==='PASS').length+'</td></tr>'
                    + '</table>';
                }
                function renderCorrectness(c){if(!c||!c.results)return '';
                  return '<h2>Correctness (C1-C8)</h2><table><tr><th>Test</th><th>Status</th><th>Duration ms</th><th>Details</th></tr>'
                    + c.results.map(x=>'<tr class="'+(x.status==='PASS'?'pass':'fail')+'"><td>'+x.name+'</td><td>'+x.status+'</td><td>'+x.durationMs+'</td><td>'+(x.details||'')+'</td></tr>').join('')
                    + '</table>';
                }
                function renderBaseline(b){if(!b)return '';
                  return '<h2>Baseline (B1-B5)</h2><table><tr><th>Scenario</th><th>TPS</th><th>p50 us</th><th>p99 us</th><th>p999 us</th><th>Errors</th></tr>'
                    + Object.entries(b).map(([k,v])=>'<tr><td>'+k+'</td><td>'+v.tps.toFixed(1)+'</td><td>'+v.p50us+'</td><td>'+v.p99us+'</td><td>'+v.p999us+'</td><td>'+v.errors+'</td></tr>').join('')
                    + '</table><canvas id="baselineChart"></canvas>';
                }
                function renderResilience(r){if(!r)return '';
                  return '<h2>Resilience (R1-R7)</h2><table><tr><th>ID</th><th>Status</th><th>Description</th><th>Observed</th></tr>'
                    + Object.entries(r).map(([k,v])=>'<tr class="'+(v.status==='PASS'?'pass':'fail')+'"><td>'+k+'</td><td>'+v.status+'</td><td>'+v.description+'</td><td><pre>'+JSON.stringify(v.observed,null,2)+'</pre></td></tr>').join('')
                    + '</table>';
                }
                function renderStress(s){if(!s)return '';return '<h2>Stress</h2><pre>'+JSON.stringify(s,null,2)+'</pre><canvas id="stressChart"></canvas>';}
                function renderSoak(s){if(!s)return '';return '<h2>Soak</h2><canvas id="soakChart"></canvas>';}
                function drawCharts(d){
                  if(d.baseline){
                    const ctx=document.getElementById('baselineChart');
                    new Chart(ctx,{type:'bar',data:{labels:Object.keys(d.baseline),datasets:[{label:'p99 us',data:Object.values(d.baseline).map(v=>v.p99us)}]}});
                  }
                  if(d.stress&&d.stress.S1){
                    const ctx=document.getElementById('stressChart');
                    new Chart(ctx,{type:'line',data:{labels:d.stress.S1.map(r=>r.threads),datasets:[{label:'TPS',data:d.stress.S1.map(r=>r.tps)},{label:'p99 us',data:d.stress.S1.map(r=>r.p99us)}]}});
                  }
                  if(d.soak&&d.soak.samples){
                    const ctx=document.getElementById('soakChart');
                    new Chart(ctx,{type:'line',data:{labels:d.soak.samples.map(s=>s.tsSec),datasets:[
                      {label:'heap MB',data:d.soak.samples.map(s=>s.heapMB)},
                      {label:'threads',data:d.soak.samples.map(s=>s.threads)}]}});
                  }
                }
                """;
    }
}
```

4. `ReportController.java`:

```java
package com.authx.clustertest.api;

import com.authx.clustertest.report.HtmlReportGenerator;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/test/report")
public class ReportController {
    private final HtmlReportGenerator gen;
    public ReportController(HtmlReportGenerator g) { this.gen = g; }

    @PostMapping("/generate")
    public Map<String, Object> generate() throws Exception {
        var path = gen.generate();
        return Map.of("path", path.toString());
    }
}
```

5. Compile and verify report renders without network:

```bash
./gradlew :cluster-test:compileJava
git add cluster-test/src/main/java/com/authx/clustertest/report/ \
        cluster-test/src/main/java/com/authx/clustertest/api/ReportController.java \
        cluster-test/src/main/resources/web/
git commit -m "feat(cluster-test): self-contained HTML report generator"
```

---

## Task T011: Orchestrator scripts

**Files:**
- Create: `cluster-test/orchestrator/run-all.sh`
- Create: `cluster-test/orchestrator/start-cluster.sh`, `stop-cluster.sh`
- Create: `cluster-test/orchestrator/run-{correctness,baseline,resilience,stress,soak}.sh`
- Create: `cluster-test/orchestrator/inject/{stall-watch,restore-watch,kill-spicedb,restore-spicedb}.sh`

**Steps:**

1. `start-cluster.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")"/.. && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
cd "$ROOT"

echo "[1/3] Starting Docker cluster..."
docker compose -f deploy/docker-compose.yml \
               -f cluster-test/deploy/docker-compose.cluster-test.yml up -d
echo "[2/3] Waiting for SpiceDB health..."
for i in $(seq 1 60); do
  curl -sf http://localhost:9090/healthz 2>/dev/null && break
  sleep 2
done

echo "[3/3] Starting 3 Spring Boot instances..."
mkdir -p "$DIR/results"
for i in 0 1 2; do
  port=$((8091 + i))
  NODE_INDEX=$i SERVER_PORT=$port \
    SPICEDB_TARGETS=localhost:50161,localhost:50162,localhost:50163 \
    TOXIPROXY_ENABLED=true \
    RESULTS_DIR="$DIR/results" \
    nohup ./gradlew :cluster-test:bootRun --console=plain > "$DIR/results/instance-$i.log" 2>&1 &
  echo "  instance $i pid=$! port=$port"
done

echo "Waiting for instances to be UP..."
for i in 0 1 2; do
  port=$((8091 + i))
  for retry in $(seq 1 60); do
    curl -sf "http://localhost:$port/actuator/health" | grep -q '"status":"UP"' && break
    sleep 2
  done
done
echo "Cluster ready."
```

2. `stop-cluster.sh`:

```bash
#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "$0")"/.. && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
cd "$ROOT"
pkill -f cluster-test || true
docker compose -f deploy/docker-compose.yml \
               -f cluster-test/deploy/docker-compose.cluster-test.yml down -v
```

3. `inject/stall-watch.sh`:

```bash
#!/usr/bin/env bash
# Apply bandwidth=0 to all SpiceDB proxies (downstream)
for p in spicedb-1 spicedb-2 spicedb-3; do
  curl -sf -X POST "http://localhost:8474/proxies/$p/toxics" \
    -d "{\"name\":\"stall\",\"type\":\"bandwidth\",\"stream\":\"downstream\",\"attributes\":{\"rate\":0}}"
done
```

4. `inject/restore-watch.sh`:

```bash
#!/usr/bin/env bash
for p in spicedb-1 spicedb-2 spicedb-3; do
  curl -sf -X DELETE "http://localhost:8474/proxies/$p/toxics/stall" || true
done
```

5. `inject/kill-spicedb.sh` and `restore-spicedb.sh` use `docker stop` / `docker start` on `spicedb-${1:-1}`.

6. `run-correctness.sh`:

```bash
#!/usr/bin/env bash
set -e
echo "[Correctness] running C1-C8 on instance-0..."
curl -sf -X POST http://localhost:8091/test/correctness/run-all | jq .
```

7. `run-baseline.sh`:

```bash
#!/usr/bin/env bash
set -e
DURATION=${DURATION:-60}
THREADS=${THREADS:-100}
for s in B1 B2 B3 B4 B5; do
  echo "[Baseline] $s ($THREADS threads, ${DURATION}s) on all 3 instances..."
  for port in 8091 8092 8093; do
    curl -sf -X POST "http://localhost:$port/test/bench/$s?threads=$THREADS&duration=$DURATION" > /dev/null &
  done
  wait
  echo "[Baseline] $s done"
done
```

8. `run-resilience.sh`:

```bash
#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
for r in R1 R2 R3 R4 R5 R6 R7; do
  echo "[Resilience] $r..."
  curl -sf -X POST "http://localhost:8091/test/resilience/$r" | jq .
  # Always restore between tests
  "$DIR/inject/restore-watch.sh" 2>/dev/null || true
done
```

9. `run-stress.sh` and `run-soak.sh` similar pattern.

10. `run-all.sh`:

```bash
#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
trap "$DIR/stop-cluster.sh" EXIT

"$DIR/start-cluster.sh"

echo "[Phase 1] Data generation + import"
curl -sf -X POST http://localhost:8091/test/data/generate
curl -sf -X POST http://localhost:8091/test/data/import

"$DIR/run-correctness.sh"
"$DIR/run-baseline.sh"
"$DIR/run-resilience.sh"
"$DIR/run-stress.sh"
"$DIR/run-soak.sh"

echo "[Phase 7] Generating HTML report..."
curl -sf -X POST http://localhost:8091/test/report/generate
echo "Report at: cluster-test/results/report.html"
```

11. Make all executable + commit:

```bash
chmod +x cluster-test/orchestrator/*.sh cluster-test/orchestrator/inject/*.sh
git add cluster-test/orchestrator/
git commit -m "feat(cluster-test): orchestrator scripts (run-all + injection)"
```

---

## Task T012: End-to-end smoke test

**Steps:**

1. Start cluster:

```bash
./cluster-test/orchestrator/start-cluster.sh
```

Wait until all 3 instances respond healthy.

2. Run a small smoke (1000 ops only):

```bash
DURATION=5 THREADS=10 ./cluster-test/orchestrator/run-baseline.sh
./cluster-test/orchestrator/run-correctness.sh
curl -sf -X POST http://localhost:8091/test/report/generate
```

3. Open `cluster-test/results/report.html` in a browser, verify:
- Environment section populated
- Correctness table shows 8 rows
- Baseline table shows 5 rows with non-zero TPS
- Charts render (offline — no network needed)

4. Stop:

```bash
./cluster-test/orchestrator/stop-cluster.sh
```

5. Commit any final fixes + add operational README:

```bash
cat > cluster-test/README.md <<'EOF'
# cluster-test

Production-grade cluster stress test harness for AuthX SDK.

## Quick start

```bash
./orchestrator/run-all.sh   # full ~60min run
```

## Single phase

```bash
./orchestrator/start-cluster.sh
DURATION=30 THREADS=50 ./orchestrator/run-baseline.sh
./orchestrator/run-resilience.sh
curl -X POST http://localhost:8091/test/report/generate
./orchestrator/stop-cluster.sh
```

See `specs/2026-04-14-cluster-stress-test/spec.md` for full requirements.
EOF
git add cluster-test/README.md
git commit -m "docs(cluster-test): add README"
```

---

## Coverage Verification

| Spec Requirement | Task(s) | Status |
|---|---|---|
| req-1: cluster bootstrap | T004, T011 (start-cluster.sh) | Covered |
| req-2: cluster-test Gradle module | T001, T002 | Covered |
| req-3: data generation + bulk import | T005 | Covered |
| req-4: correctness C1-C8 | T006 | Covered |
| req-5: baseline B1-B5 | T007 | Covered |
| req-6: resilience R1-R7 | T008 | Covered |
| req-7: stress S1-S2 | T009 | Covered |
| req-8: soak L1 | T009 | Covered |
| req-9: HTML report | T010 | Covered |
| req-10: orchestrator scripts | T011 | Covered |
