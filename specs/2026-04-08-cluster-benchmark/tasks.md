# 分布式集群部署 + 千万级压测 — Task Checklist

> Track progress by marking items `[X]` as completed.

## Phase 1: Module Setup
- [X] T001 [SR:req-2] Register cluster-test submodule + build.gradle — `settings.gradle`, `cluster-test/build.gradle`
- [X] T002 [SR:req-2] Spring Boot app + SDK config + application.yml — `ClusterTestApp.java`, `SdkConfig.java`, `application.yml`

## Phase 2: Data Generation + Import
- [X] T003 [SR:req-3] DataModel + RelationshipFileGenerator — `generator/DataModel.java`, `generator/RelationshipFileGenerator.java`
- [X] T004 [SR:req-3] BulkImporter (gRPC streaming) — `generator/BulkImporter.java`

## Phase 3: REST API
- [X] T005 [SR:req-3,req-4,req-5] TestController endpoints — `api/TestController.java`

## Phase 4: Benchmark Engine
- [X] T006 [SR:req-4] BenchmarkRunner + BenchmarkResult + ReadBenchmark (B1) — `benchmark/BenchmarkRunner.java`, `benchmark/BenchmarkResult.java`, `benchmark/ReadBenchmark.java`
- [X] T007 [P] [SR:req-4] WriteBenchmark (B2) + ConsistencyBenchmark (B3) — `benchmark/WriteBenchmark.java`, `benchmark/ConsistencyBenchmark.java`
- [X] T008 [P] [SR:req-4] DeepInheritanceBenchmark (B4) + BatchBenchmark (B5) + FaultRecoveryBenchmark (B6) — `benchmark/DeepInheritanceBenchmark.java`, `benchmark/BatchBenchmark.java`, `benchmark/FaultRecoveryBenchmark.java`

## Phase 5: Correctness Verification
- [X] T009 [SR:req-5] CorrectnessVerifier — `verify/CorrectnessVerifier.java`

## Phase 6: Monitoring Stack
- [X] T010 [P] [SR:req-1] Prometheus config + Docker Compose update — `deploy/prometheus/prometheus.yml`, `deploy/docker-compose.yml`
- [X] T011 [P] [SR:req-1] Grafana provisioning + 3 dashboards — `deploy/grafana/**`

## Phase 7: Scripts + Verification
- [X] T012 [SR:req-6] Start/stop scripts — `cluster-test/scripts/start-cluster.sh`, `cluster-test/scripts/stop-cluster.sh`
- [X] T013 Final compile + smoke test

## Dependencies

```
T002 depends on T001 (module must exist)
T003, T004 depend on T002 (need Spring context)
T005 depends on T003, T004 (controller calls generator/importer)
T006 depends on T002 (needs SDK config)
T007, T008 depend on T006 (need BenchmarkRunner)
T007 and T008 are parallelizable [P]
T009 depends on T002 (needs SDK config)
T010 and T011 are parallelizable [P] (independent of Java code)
T012 depends on T002
T013 depends on all
```

## Coverage Matrix

| Spec Requirement | Task(s) | Status |
|---|---|---|
| req-1: Docker Compose monitoring stack | T010, T011 | Covered |
| req-2: cluster-test Gradle submodule | T001, T002 | Covered |
| req-3: Data generation + bulk import | T003, T004, T005 | Covered |
| req-4: Benchmark engine (B1-B6) | T005, T006, T007, T008 | Covered |
| req-5: Correctness verification | T005, T009 | Covered |
| req-6: Start/stop scripts | T012 | Covered |
