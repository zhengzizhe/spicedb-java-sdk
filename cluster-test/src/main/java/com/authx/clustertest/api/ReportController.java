package com.authx.clustertest.api;

import com.authx.clustertest.report.HtmlReportGenerator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
