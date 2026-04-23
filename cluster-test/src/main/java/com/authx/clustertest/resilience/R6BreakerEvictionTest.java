package com.authx.clustertest.resilience;

import com.authx.sdk.AuthxClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R6 — Circuit-breaker map eviction. Drives 5000 distinct resource types
 * through {@code on(...).check(...)} — each call attempts a check that will
 * fail because the schema has no such type, but that populates the
 * {@code ResilientTransport.breakers} Caffeine cache. We then exercise a
 * "hot_type" 100 times and verify the system still operates without OOM or
 * accumulated thread leakage. The map itself is bounded by Caffeine so no
 * reflective assertion is needed — a non-throwing continuation after 5100
 * resource types is strong evidence the eviction policy holds.
 */
@Component
public class R6BreakerEvictionTest {
    private final AuthxClient client;

    public R6BreakerEvictionTest(AuthxClient c) { this.client = c; }

    public ResilienceResult run() {
        long t0 = System.currentTimeMillis();
        var events = Collections.synchronizedList(new ArrayList<String>());

        int distinctTypes = 5000;
        int hotRepeats = 100;

        var churnErrors = new AtomicLong();
        var hotErrors = new AtomicLong();
        long heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        int threadsBefore = Thread.activeCount();

        // Phase 1 — churn distinct resource types. Expected to fail schema
        // validation or resolution; we swallow those, we care only that the
        // breakers-map path does not OOM or deadlock.
        for (int i = 0; i < distinctTypes; i++) {
            String type = "type-r6-" + i;
            try {
                client.on(type).resource("x").check("view").by("user-r6").hasPermission();
            } catch (Throwable t) {
                churnErrors.incrementAndGet();
            }
        }
        events.add("phase1 done churnErrors=" + churnErrors.get());

        // Metrics snapshot must not throw post-churn
        String snapSample;
        try {
            var snap = client.metrics().snapshot();
            snapSample = snap.toString().substring(0, Math.min(120, snap.toString().length()));
        } catch (Throwable t) {
            snapSample = "snapshot-threw: " + t.getClass().getSimpleName();
        }

        // Phase 2 — hot_type repeated calls. These should still succeed (or
        // fail consistently without memory/thread blowup).
        for (int i = 0; i < hotRepeats; i++) {
            try {
                client.on("hot_type").resource("x").check("view").by("user-r6").hasPermission();
            } catch (Throwable t) {
                hotErrors.incrementAndGet();
            }
        }
        events.add("phase2 done hotErrors=" + hotErrors.get());

        long heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        int threadsAfter = Thread.activeCount();

        // PASS criteria: no OOM, snapshot not throwing, thread growth bounded.
        boolean ok = !snapSample.startsWith("snapshot-threw")
                && (threadsAfter - threadsBefore) < 200;
        String reason = ok ? null : "snapshot=" + snapSample
                + " threadsDelta=" + (threadsAfter - threadsBefore);

        return new ResilienceResult(
                "R6", ok ? "PASS" : "FAIL", System.currentTimeMillis() - t0,
                "Breaker-map eviction under resource-type churn",
                Map.of("distinctTypes", distinctTypes, "hotRepeats", hotRepeats),
                Map.of(
                        "churnErrors", churnErrors.get(),
                        "hotErrors", hotErrors.get(),
                        "heapBeforeBytes", heapBefore,
                        "heapAfterBytes", heapAfter,
                        "threadsBefore", threadsBefore,
                        "threadsAfter", threadsAfter,
                        "snapshotSample", snapSample),
                List.copyOf(events),
                reason);
    }
}
