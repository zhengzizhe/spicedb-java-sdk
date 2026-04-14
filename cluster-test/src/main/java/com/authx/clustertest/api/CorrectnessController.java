package com.authx.clustertest.api;

import com.authx.clustertest.config.ResultsRepo;
import com.authx.clustertest.correctness.CorrectnessResult;
import com.authx.clustertest.correctness.CorrectnessRunner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/test/correctness")
public class CorrectnessController {
    private final CorrectnessRunner runner;
    private final ResultsRepo repo;

    public CorrectnessController(CorrectnessRunner runner, ResultsRepo repo) {
        this.runner = runner;
        this.repo = repo;
    }

    @PostMapping("/run-all")
    public Map<String, Object> runAll() {
        List<CorrectnessResult> results = runner.runAll();
        long passed = results.stream().filter(r -> "PASS".equals(r.status())).count();
        Map<String, Object> summary = Map.of(
                "total", results.size(),
                "passed", passed,
                "results", results);
        repo.write("correctness", summary);
        return summary;
    }
}
