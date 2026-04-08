package com.authx.cluster.api;

import com.authx.cluster.benchmark.BatchBenchmark;
import com.authx.cluster.benchmark.BenchmarkResult;
import com.authx.cluster.benchmark.ConsistencyBenchmark;
import com.authx.cluster.benchmark.DeepInheritanceBenchmark;
import com.authx.cluster.benchmark.FaultRecoveryBenchmark;
import com.authx.cluster.benchmark.ReadBenchmark;
import com.authx.cluster.benchmark.WriteBenchmark;
import com.authx.cluster.generator.BulkImporter;
import com.authx.cluster.generator.RelationshipFileGenerator;
import com.authx.cluster.verify.CorrectnessVerifier;
import com.authx.sdk.AuthxClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@RestController
public class TestController {

    private static final System.Logger LOG = System.getLogger(TestController.class.getName());

    private final AuthxClient client;
    private final RelationshipFileGenerator generator;
    private final BulkImporter importer;
    private final ReadBenchmark readBenchmark;
    private final WriteBenchmark writeBenchmark;
    private final ConsistencyBenchmark consistencyBenchmark;
    private final DeepInheritanceBenchmark deepInheritanceBenchmark;
    private final BatchBenchmark batchBenchmark;
    private final FaultRecoveryBenchmark faultRecoveryBenchmark;
    private final CorrectnessVerifier verifier;
    private final int nodeIndex;

    private final AtomicReference<String> status = new AtomicReference<>("IDLE");
    private final List<BenchmarkResult> benchmarkResults = new ArrayList<>();

    public TestController(AuthxClient client,
                          RelationshipFileGenerator generator,
                          BulkImporter importer,
                          ReadBenchmark readBenchmark,
                          WriteBenchmark writeBenchmark,
                          ConsistencyBenchmark consistencyBenchmark,
                          DeepInheritanceBenchmark deepInheritanceBenchmark,
                          BatchBenchmark batchBenchmark,
                          FaultRecoveryBenchmark faultRecoveryBenchmark,
                          CorrectnessVerifier verifier,
                          @Value("${cluster.node-index:0}") int nodeIndex) {
        this.client = client;
        this.generator = generator;
        this.importer = importer;
        this.readBenchmark = readBenchmark;
        this.writeBenchmark = writeBenchmark;
        this.consistencyBenchmark = consistencyBenchmark;
        this.deepInheritanceBenchmark = deepInheritanceBenchmark;
        this.batchBenchmark = batchBenchmark;
        this.faultRecoveryBenchmark = faultRecoveryBenchmark;
        this.verifier = verifier;
        this.nodeIndex = nodeIndex;
    }

    // ---- Data Generation + Import ----

    @PostMapping("/test/generate")
    public Map<String, Object> generate() {
        status.set("GENERATING");
        long start = System.currentTimeMillis();
        try {
            Path file = Path.of("relationships-node-" + nodeIndex + ".txt");
            long count = generator.generate(file);
            long durationMs = System.currentTimeMillis() - start;
            status.set("IDLE");
            return Map.of("count", count, "file", file.toString(), "durationMs", durationMs);
        } catch (Exception e) {
            status.set("ERROR: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @PostMapping("/test/import")
    public Map<String, Object> importData(@RequestParam(defaultValue = "relationships-node-0.txt") String file) {
        status.set("IMPORTING");
        try {
            var result = importer.importFile(Path.of(file));
            status.set("IDLE");
            return Map.of("imported", result.imported(), "durationMs", result.durationMs());
        } catch (Exception e) {
            status.set("ERROR: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ---- Benchmarks ----

    @PostMapping("/test/benchmark/{scenario}")
    public BenchmarkResult benchmark(@PathVariable String scenario) {
        status.set("BENCHMARKING: " + scenario);
        try {
            BenchmarkResult result = switch (scenario.toUpperCase()) {
                case "B1" -> readBenchmark.run();
                case "B2" -> writeBenchmark.run();
                case "B3" -> consistencyBenchmark.run();
                case "B4" -> deepInheritanceBenchmark.run();
                case "B5" -> batchBenchmark.run();
                case "B6" -> faultRecoveryBenchmark.run();
                default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
            };
            synchronized (benchmarkResults) {
                benchmarkResults.add(result);
            }
            status.set("IDLE");
            return result;
        } catch (Exception e) {
            status.set("ERROR: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @PostMapping("/test/benchmark/all")
    public List<BenchmarkResult> benchmarkAll() {
        var results = new ArrayList<BenchmarkResult>();
        for (String s : List.of("B1", "B2", "B3", "B4", "B5")) {
            // B6 (fault recovery) excluded from "all" — requires manual intervention
            results.add(benchmark(s));
        }
        return results;
    }

    @GetMapping("/test/benchmark/report")
    public List<BenchmarkResult> report() {
        synchronized (benchmarkResults) {
            return List.copyOf(benchmarkResults);
        }
    }

    // ---- Correctness Verification ----

    @PostMapping("/test/verify")
    public List<CorrectnessVerifier.VerifyResult> verify() {
        status.set("VERIFYING");
        try {
            var results = verifier.runAll();
            status.set("IDLE");
            return results;
        } catch (Exception e) {
            status.set("ERROR: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ---- Status + Health ----

    @GetMapping("/test/status")
    public Map<String, Object> status() {
        return Map.of("node", nodeIndex, "status", status.get());
    }

    @GetMapping("/test/check")
    public Map<String, Object> check(@RequestParam String type,
                                      @RequestParam String id,
                                      @RequestParam String permission,
                                      @RequestParam String user) {
        boolean allowed = client.on(type).check(id, permission, user);
        return Map.of("allowed", allowed);
    }

    @GetMapping("/test/health")
    public Map<String, Object> health() {
        var h = client.health();
        return Map.of("node", nodeIndex, "healthy", h.isHealthy(),
                "latencyMs", h.spicedbLatencyMs(), "details", h.details());
    }
}
