package com.authx.clustertest.api;

import com.authx.clustertest.config.ResultsRepo;
import com.authx.clustertest.resilience.R1StreamLeakTest;
import com.authx.clustertest.resilience.R4TokenStoreTest;
import com.authx.clustertest.resilience.R5DoubleDeleteTest;
import com.authx.clustertest.resilience.R6BreakerEvictionTest;
import com.authx.clustertest.resilience.R7CloseRobustnessTest;
import com.authx.clustertest.resilience.ResilienceResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Resilience test entry point. Note: R2 (CursorExpired) and R3 (StreamStale)
 * were removed with the Watch subsystem in ADR 2026-04-18.
 */
@RestController
@RequestMapping("/test/resilience")
public class ResilienceController {
    private final R1StreamLeakTest r1;
    private final R4TokenStoreTest r4;
    private final R5DoubleDeleteTest r5;
    private final R6BreakerEvictionTest r6;
    private final R7CloseRobustnessTest r7;
    private final ResultsRepo repo;

    public ResilienceController(R1StreamLeakTest r1,
                                 R4TokenStoreTest r4, R5DoubleDeleteTest r5, R6BreakerEvictionTest r6,
                                 R7CloseRobustnessTest r7, ResultsRepo repo) {
        this.r1 = r1; this.r4 = r4;
        this.r5 = r5; this.r6 = r6; this.r7 = r7; this.repo = repo;
    }

    @PostMapping("/{id}")
    public ResilienceResult run(@PathVariable String id) throws Exception {
        ResilienceResult r = switch (id) {
            case "R1" -> r1.run();
            case "R4" -> r4.run();
            case "R5" -> r5.run();
            case "R6" -> r6.run();
            case "R7" -> r7.run();
            default -> throw new IllegalArgumentException("unknown resilience id: " + id);
        };
        @SuppressWarnings("unchecked")
        Map<String, Object> existing = repo.read("resilience", Map.class);
        Map<String, Object> all = existing != null ? new HashMap<>(existing) : new HashMap<>();
        all.put(id, r);
        repo.write("resilience", all);
        return r;
    }
}
