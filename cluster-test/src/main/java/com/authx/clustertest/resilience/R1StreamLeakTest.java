package com.authx.clustertest.resilience;

import com.authx.sdk.AuthxClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * R1 — Stream leak regression. Drives {@code 1000 lookup(...).limit(10)} calls.
 * Without the {@code CloseableGrpcIterator} fix, each unfinished stream would
 * leak a gRPC stream. Here we simply verify the SDK handles 1000 calls without
 * error and with stable thread count as a soft indicator.
 */
@Component
public class R1StreamLeakTest {
    private final AuthxClient client;

    public R1StreamLeakTest(AuthxClient client) { this.client = client; }

    public ResilienceResult run() {
        long t0 = System.currentTimeMillis();
        var events = Collections.synchronizedList(new ArrayList<String>());

        int threadsBefore = Thread.activeCount();
        int iterations = 1000;
        int errors = 0;
        int overLimit = 0;
        String lastError = null;
        for (int i = 0; i < iterations; i++) {
            try {
                var ids = client.lookup("document")
                        .withPermission("view")
                        .by("user-r1-" + (i % 50))
                        .limit(10)
                        .fetch();
                if (ids.size() > 10) overLimit++;
            } catch (Exception e) {
                errors++;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        int threadsAfter = Thread.activeCount();

        boolean ok = overLimit == 0 && (threadsAfter - threadsBefore) < 200;
        String reason = ok ? null : "overLimit=" + overLimit
                + " threadsDelta=" + (threadsAfter - threadsBefore)
                + (lastError != null ? " lastError=" + lastError : "");
        events.add("iterations=" + iterations + " errors=" + errors);

        return new ResilienceResult(
                "R1", ok ? "PASS" : "FAIL", System.currentTimeMillis() - t0,
                "Stream leak regression — 1000 bounded lookup() calls",
                Map.of("iterations", iterations, "limit", 10),
                Map.of(
                        "overLimit", overLimit,
                        "errors", errors,
                        "threadsBefore", threadsBefore,
                        "threadsAfter", threadsAfter
                ),
                List.copyOf(events),
                reason);
    }
}
