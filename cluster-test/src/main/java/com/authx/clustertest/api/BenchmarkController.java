package com.authx.clustertest.api;

import com.authx.clustertest.benchmark.B1ReadBenchmark;
import com.authx.clustertest.benchmark.B2WriteBenchmark;
import com.authx.clustertest.benchmark.B3ConsistencyBenchmark;
import com.authx.clustertest.benchmark.B4DeepInheritanceBenchmark;
import com.authx.clustertest.benchmark.B5BatchBenchmark;
import com.authx.clustertest.benchmark.BenchmarkResult;
import com.authx.clustertest.config.ResultsRepo;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Baseline benchmark REST API. Each POST runs one scenario (B1-B5) and
 * merges the result into the shared {@code baseline.json} file via
 * {@link ResultsRepo}.
 */
@RestController
@RequestMapping("/test/bench")
public class BenchmarkController {

    private final B1ReadBenchmark b1;
    private final B2WriteBenchmark b2;
    private final B3ConsistencyBenchmark b3;
    private final B4DeepInheritanceBenchmark b4;
    private final B5BatchBenchmark b5;
    private final ResultsRepo repo;

    public BenchmarkController(B1ReadBenchmark b1, B2WriteBenchmark b2, B3ConsistencyBenchmark b3,
                               B4DeepInheritanceBenchmark b4, B5BatchBenchmark b5, ResultsRepo repo) {
        this.b1 = b1;
        this.b2 = b2;
        this.b3 = b3;
        this.b4 = b4;
        this.b5 = b5;
        this.repo = repo;
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
        @SuppressWarnings("unchecked")
        Map<String, Object> existing = repo.read("baseline", Map.class);
        Map<String, Object> all = existing != null ? new HashMap<>(existing) : new HashMap<>();
        all.put(id, r);
        repo.write("baseline", all);
        return r;
    }
}
