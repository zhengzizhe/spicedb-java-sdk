package com.authx.clustertest.watchstorm;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.RelationshipChange;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Watch propagation under sustained write load.
 *
 * <p>Spawns N independent {@link AuthxClient} instances against the same SpiceDB
 * cluster (each with its own cache + Watch stream). One writer pushes 100/s
 * grants for 30s; the N readers record how long after each write their Watch
 * event arrives.
 *
 * <p>Measures: per-reader p50/p99/max watch-propagation latency. If Watch keeps
 * up, p99 should be sub-100ms; if SpiceDB or the network falls behind, it grows.
 *
 * <p>Pre-requisite: {@code ./deploy/cluster-up.sh} has been run.
 *
 * <p><b>Note:</b> Same JVM is used (each AuthxClient is independent — own cache,
 * own gRPC channel, own Watch dispatcher). This is functionally equivalent to N
 * separate JVMs from a Watch propagation standpoint.
 */
class WatchStormIT {

    private static final String[] TARGETS = {"localhost:50051", "localhost:50052", "localhost:50053"};
    private static final String PSK = "testkey";
    private static final int N_READERS = 3;
    private static final int WRITES_PER_SEC = 100;
    private static final Duration RUN_FOR = Duration.ofSeconds(30);

    private static AuthxClient writer;
    private static AuthxClient[] readers;

    @BeforeAll
    static void up() {
        writer = AuthxClient.builder()
                .connection(c -> c.targets(TARGETS).presharedKey(PSK)
                        .requestTimeout(Duration.ofSeconds(15)))
                .cache(c -> c.enabled(false))   // writer doesn't need cache
                .features(f -> f.shutdownHook(false))
                .build();

        readers = new AuthxClient[N_READERS];
        for (int i = 0; i < N_READERS; i++) {
            readers[i] = AuthxClient.builder()
                    .connection(c -> c.targets(TARGETS).presharedKey(PSK)
                            .requestTimeout(Duration.ofSeconds(15)))
                    .cache(c -> c.enabled(true).maxSize(100_000).watchInvalidation(true))
                    .features(f -> f.shutdownHook(false))
                    .build();
        }
        // Give Watch streams time to attach.
        try { Thread.sleep(2_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    @AfterAll
    static void down() {
        if (writer != null) try { writer.close(); } catch (Exception ignored) { }
        if (readers != null) for (var r : readers) try { r.close(); } catch (Exception ignored) { }
    }

    @Test
    void watchPropagationUnderLoad() throws Exception {
        // Per-write timestamps keyed by resourceId; readers lookup on event arrival.
        var writeTimesNs = new ConcurrentHashMap<String, Long>();

        // Per-reader latency histogram: write-completion → watch-event arrival.
        Histogram[] readerHist = new Histogram[N_READERS];
        AtomicLong[] eventsSeen = new AtomicLong[N_READERS];
        for (int i = 0; i < N_READERS; i++) {
            readerHist[i] = new Histogram(60_000_000_000L, 3);
            eventsSeen[i] = new AtomicLong();
            final int idx = i;
            readers[i].onRelationshipChange((RelationshipChange ev) -> {
                long now = System.nanoTime();
                Long writeNs = writeTimesNs.get(ev.resourceId());
                if (writeNs != null) {
                    long latencyUs = (now - writeNs) / 1_000;
                    synchronized (readerHist[idx]) {
                        readerHist[idx].recordValue(Math.min(latencyUs, 60_000_000));
                    }
                    eventsSeen[idx].incrementAndGet();
                }
            });
        }

        // Writer loop — 100/sec for RUN_FOR.
        long deadline = System.nanoTime() + RUN_FOR.toNanos();
        long intervalNs = 1_000_000_000L / WRITES_PER_SEC;
        long scheduled = System.nanoTime();
        AtomicLong writesSent = new AtomicLong();
        var pool = Executors.newFixedThreadPool(8);

        while (scheduled < deadline) {
            long t = scheduled;
            long now = System.nanoTime();
            if (t > now) {
                long sleep = t - now;
                if (sleep > 1_000_000) Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
                else while (System.nanoTime() < t) Thread.onSpinWait();
            }
            String resId = "ws-" + writesSent.get();
            String userId = "ws-u-" + writesSent.get();
            pool.submit(() -> {
                writeTimesNs.put(resId, System.nanoTime());
                try {
                    writer.on("document").grant(resId, "viewer", userId);
                } catch (Exception ignored) { }
            });
            writesSent.incrementAndGet();
            scheduled += intervalNs;
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // SpiceDB's revision-quantization-interval (5s in our cluster config) batches
        // watch events; tail events from the last write window land up to ~5s after
        // the writer stops. Wait long enough for the trailing batch to drain.
        Thread.sleep(12_000);

        // Report.
        long expectedWrites = writesSent.get();
        System.out.println("[WatchStorm] writes_sent=" + expectedWrites);
        for (int i = 0; i < N_READERS; i++) {
            long seen = eventsSeen[i].get();
            double seenPct = seen * 100.0 / expectedWrites;
            Histogram h = readerHist[i];
            System.out.printf(
                    "[WatchStorm] reader-%d  events=%d (%.1f%%)  p50=%.2fms  p99=%.2fms  p999=%.2fms  max=%.2fms%n",
                    i, seen, seenPct,
                    h.getValueAtPercentile(50) / 1000.0,
                    h.getValueAtPercentile(99) / 1000.0,
                    h.getValueAtPercentile(99.9) / 1000.0,
                    h.getMaxValue() / 1000.0);
        }

        // Sanity asserts. Thresholds reflect the cluster config:
        // - 95%+ delivery (we wait long enough for the last quantization window)
        // - p99 < 10s (5s quantization + 5s dispatch headroom)
        for (int i = 0; i < N_READERS; i++) {
            long seen = eventsSeen[i].get();
            assertThat(seen).as("reader-%d events received", i)
                    .isGreaterThan((long) (expectedWrites * 0.95));
            assertThat(readerHist[i].getValueAtPercentile(99))
                    .as("reader-%d p99 latency (us)", i)
                    .isLessThan(10_000_000L);
        }
    }
}
