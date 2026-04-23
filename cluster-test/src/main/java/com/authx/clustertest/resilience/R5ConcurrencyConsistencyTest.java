package com.authx.clustertest.resilience;

import com.authx.sdk.AuthxClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R5 — Concurrent grant/revoke + read consistency. 100 writers thrash
 * grant/revoke on 50 docs while 100 readers check concurrently. Writers
 * atomically update an expected-state map; readers record any mismatches.
 * After a 1s settle, do a final authoritative pass.
 */
@Component
public class R5ConcurrencyConsistencyTest {
    private final AuthxClient client;

    public R5ConcurrencyConsistencyTest(AuthxClient c) { this.client = c; }

    public ResilienceResult run() throws Exception {
        long t0 = System.currentTimeMillis();
        var events = Collections.synchronizedList(new ArrayList<String>());
        int docs = 50, writers = 100, readers = 100;
        long runMs = 30_000L;

        // Key: docId + "|" + userId → expected current boolean state (has viewer rel)
        var expected = new ConcurrentHashMap<String, Boolean>();
        for (int i = 1; i <= docs; i++) {
            expected.put("doc-r5-" + i + "|user-r5", Boolean.FALSE);
        }

        var runMismatches = new AtomicLong();
        var reads = new AtomicLong();
        var writes = new AtomicLong();
        var writeErrs = new AtomicLong();
        var readErrs = new AtomicLong();

        var stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        for (int w = 0; w < writers; w++) {
            pool.submit(() -> {
                awaitQuietly(start);
                var rnd = ThreadLocalRandom.current();
                while (!stop.get()) {
                    int id = 1 + rnd.nextInt(docs);
                    String doc = "doc-r5-" + id;
                    String user = "user-r5";
                    String key = doc + "|" + user;
                    boolean grant = rnd.nextBoolean();
                    try {
                        if (grant) {
                            client.on("document").resource(doc).grant("viewer").to(user);
                        } else {
                            client.on("document").resource(doc).revoke("viewer").from(user);
                        }
                        expected.put(key, grant);
                        writes.incrementAndGet();
                    } catch (Exception e) {
                        writeErrs.incrementAndGet();
                    }
                }
            });
        }

        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                awaitQuietly(start);
                var rnd = ThreadLocalRandom.current();
                while (!stop.get()) {
                    int id = 1 + rnd.nextInt(docs);
                    String doc = "doc-r5-" + id;
                    String user = "user-r5";
                    String key = doc + "|" + user;
                    try {
                        boolean actual = client.on("document").resource(doc).check("view").by(user).hasPermission();
                        Boolean exp = expected.get(key);
                        reads.incrementAndGet();
                        // A mismatch during contention is expected (writes race
                        // with reads). We record but don't fail on these.
                        if (exp != null && exp != actual) {
                            runMismatches.incrementAndGet();
                        }
                    } catch (Exception e) {
                        readErrs.incrementAndGet();
                    }
                }
            });
        }

        start.countDown();
        Thread.sleep(runMs);
        stop.set(true);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // Settle window then final authoritative read of every (doc, user) key
        Thread.sleep(1000);
        int finalMismatches = 0;
        for (var e : expected.entrySet()) {
            var parts = e.getKey().split("\\|", 2);
            String doc = parts[0], user = parts[1];
            try {
                boolean actual = client.on("document").resource(doc).check("view").by(user).hasPermission();
                if (actual != e.getValue()) {
                    finalMismatches++;
                    events.add("final-mismatch " + e.getKey() + " expected=" + e.getValue() + " actual=" + actual);
                }
            } catch (Exception ex) {
                readErrs.incrementAndGet();
            }
        }

        boolean ok = finalMismatches == 0;
        return new ResilienceResult(
                "R5", ok ? "PASS" : "FAIL", System.currentTimeMillis() - t0,
                "Concurrent grant/revoke correctness under contention",
                Map.of("docs", docs, "writers", writers, "readers", readers, "runMs", runMs),
                Map.of(
                        "writes", writes.get(),
                        "reads", reads.get(),
                        "writeErrors", writeErrs.get(),
                        "readErrors", readErrs.get(),
                        "runMismatches", runMismatches.get(),
                        "finalMismatches", finalMismatches),
                List.copyOf(events),
                ok ? null : "final authoritative pass produced " + finalMismatches + " mismatches");
    }

    private static void awaitQuietly(CountDownLatch l) {
        try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
