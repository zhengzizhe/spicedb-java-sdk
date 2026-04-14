# Cluster Stress Test — Tasks

## Phase 1: Module Foundation
- [ ] T001 [SR:req-2] Create cluster-test Gradle submodule + build.gradle — `settings.gradle`, `cluster-test/build.gradle`
- [ ] T002 [SR:req-2] Spring Boot app + AuthxClient bean + application.yml — `cluster-test/src/main/java/com/authx/clustertest/{ClusterTestApp,config/ClusterProps,config/SdkConfig}.java`
- [ ] T003 [SR:req-2] ResultsRepo (per-instance JSON output) — `cluster-test/src/main/java/com/authx/clustertest/config/ResultsRepo.java`

## Phase 2: Cluster Infrastructure
- [ ] T004 [SR:req-1] Docker Compose extension + Toxiproxy — `cluster-test/deploy/docker-compose.cluster-test.yml`, `cluster-test/deploy/toxiproxy-init.sh`

## Phase 3: Data Pipeline
- [ ] T005 [SR:req-3] Data generator + bulk importer + DataController — `cluster-test/src/main/java/com/authx/clustertest/data/`, `api/DataController.java`

## Phase 4: Test Suites (parallelizable — all build on T002+T003+T005)
- [ ] T006 [P] [SR:req-4] Correctness C1-C8 + CorrectnessController — `cluster-test/src/main/java/com/authx/clustertest/correctness/`, `api/CorrectnessController.java`
- [ ] T007 [P] [SR:req-5] Benchmark B1-B5 + ScenarioRunner + BenchmarkController — `cluster-test/src/main/java/com/authx/clustertest/benchmark/`, `api/BenchmarkController.java`
- [ ] T008 [P] [SR:req-6] Resilience R1-R7 (toxiproxy) + ResilienceController — `cluster-test/src/main/java/com/authx/clustertest/resilience/`, `api/ResilienceController.java`
- [ ] T009 [P] [SR:req-7,req-8] Stress S1-S2 + Soak L1 + their controllers — `cluster-test/src/main/java/com/authx/clustertest/{stress,soak}/`, `api/{StressController,SoakController}.java`

## Phase 5: Reporting
- [ ] T010 [SR:req-9] HTML report generator + vendored Chart.js + ReportController — `cluster-test/src/main/java/com/authx/clustertest/report/`, `web/chart.min.js`, `api/ReportController.java`

## Phase 6: Orchestration
- [ ] T011 [SR:req-10] Orchestrator scripts (run-all + start/stop + per-phase + inject/) — `cluster-test/orchestrator/`

## Phase 7: Validation
- [ ] T012 End-to-end smoke (5min run) + README — `cluster-test/README.md`

## Dependencies

```
T002 depends on T001
T003 depends on T002
T004 depends on T001
T005 depends on T002, T003, T004 (needs SpiceDB up to import)
T006, T007, T008, T009 depend on T002, T003 — independent of each other [P]
T010 depends on T006, T007, T008, T009 (reads their JSON output)
T011 depends on T004 + all controllers (T005-T010)
T012 depends on T011
```

## Coverage Matrix

| Spec Requirement | Task(s) | Status |
|---|---|---|
| req-1: Cluster bootstrap | T004, T011 | Covered |
| req-2: cluster-test module | T001, T002, T003 | Covered |
| req-3: Data generation + import | T005 | Covered |
| req-4: Correctness C1-C8 | T006 | Covered |
| req-5: Baseline B1-B5 | T007 | Covered |
| req-6: Resilience R1-R7 | T008 | Covered |
| req-7: Stress S1-S2 | T009 | Covered |
| req-8: Soak L1 | T009 | Covered |
| req-9: HTML report | T010 | Covered |
| req-10: Orchestrator scripts | T011 | Covered |
