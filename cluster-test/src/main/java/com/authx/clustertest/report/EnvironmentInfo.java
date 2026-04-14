package com.authx.clustertest.report;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class EnvironmentInfo {
    public Map<String, Object> snapshot() {
        var p = System.getProperties();
        var rt = Runtime.getRuntime();
        var info = new LinkedHashMap<String, Object>();
        info.put("javaVersion", p.getProperty("java.version"));
        info.put("javaVendor", p.getProperty("java.vendor"));
        info.put("os", p.getProperty("os.name") + " " + p.getProperty("os.version"));
        info.put("arch", p.getProperty("os.arch"));
        info.put("cores", rt.availableProcessors());
        info.put("maxHeapMB", rt.maxMemory() / 1024 / 1024);
        info.put("generatedAt", java.time.Instant.now().toString());
        return info;
    }
}
