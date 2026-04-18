package com.authx.clustertest.api;

import com.authx.clustertest.benchmark.BenchmarkResult;
import com.authx.clustertest.config.ResultsRepo;
import com.authx.clustertest.stress.S1RampTest;
import com.authx.clustertest.stress.S2SustainedTest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/test/stress")
public class StressController {

    private final S1RampTest s1;
    private final S2SustainedTest s2;
    private final ResultsRepo repo;

    public StressController(S1RampTest s1, S2SustainedTest s2, ResultsRepo repo) {
        this.s1 = s1;
        this.s2 = s2;
        this.repo = repo;
    }

    @PostMapping("/{id}")
    public Object run(@PathVariable String id,
                      @RequestParam(defaultValue = "500") int threads,
                      @RequestParam(defaultValue = "300") int duration) throws Exception {
        long durationMs = duration * 1000L;
        Object result = switch (id) {
            case "S1" -> s1.run();
            case "S2" -> s2.run(threads, durationMs);
            default -> throw new IllegalArgumentException("unknown stress scenario: " + id);
        };
        persist(id, result);
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void persist(String id, Object result) {
        Map existing = repo.read("stress", Map.class);
        Map<String, Object> all = existing != null ? new HashMap<>(existing) : new HashMap<>();
        if (id.equals("S1")) {
            all.put("S1", (List<BenchmarkResult>) result);
        } else {
            all.put("S2", (BenchmarkResult) result);
        }
        repo.write("stress", all);
    }
}
