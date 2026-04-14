package com.authx.clustertest.resilience;

import java.util.List;
import java.util.Map;

public record ResilienceResult(
        String id, String status, long durationMs, String description,
        Map<String, Object> injection,
        Map<String, Object> observed,
        List<String> events,
        String reason
) {}
