package com.authx.clustertest.api;

import com.authx.clustertest.config.ResultsRepo;
import com.authx.clustertest.soak.L1SoakTest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/test/soak")
public class SoakController {

    private final L1SoakTest l1;
    private final ResultsRepo repo;

    public SoakController(L1SoakTest l1, ResultsRepo repo) {
        this.l1 = l1;
        this.repo = repo;
    }

    @PostMapping("/L1")
    public Map<String, Object> runL1(@RequestParam(defaultValue = "30") int durationMinutes) throws Exception {
        Map<String, Object> result = l1.run(durationMinutes);
        repo.write("soak", Map.of("L1", result));
        return result;
    }
}
