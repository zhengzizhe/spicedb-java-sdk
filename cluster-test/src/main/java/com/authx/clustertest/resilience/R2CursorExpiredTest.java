package com.authx.clustertest.resilience;

import com.authx.clustertest.config.ClusterProps;
import com.authx.sdk.AuthxClient;
import com.authx.sdk.event.SdkTypedEvent;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * R2 — Watch cursor expiry detection. Stalls Watch via toxiproxy for 2 minutes
 * and expects either {@link SdkTypedEvent.WatchCursorExpired} or (acceptable
 * earlier-fire fallback) {@link SdkTypedEvent.WatchStreamStale} — both are
 * protective signals.
 */
@Component
public class R2CursorExpiredTest {
    private final AuthxClient client;
    private final ClusterProps props;

    public R2CursorExpiredTest(AuthxClient c, ClusterProps p) { this.client = c; this.props = p; }

    public ResilienceResult run() throws Exception {
        long t0 = System.currentTimeMillis();
        var events = Collections.synchronizedList(new ArrayList<String>());

        if (!props.toxiproxy().enabled()) {
            return new ResilienceResult("R2", "SKIPPED", System.currentTimeMillis() - t0,
                    "Watch cursor expiry via toxiproxy stall",
                    Map.of(), Map.of(), List.of(), "toxiproxy not configured");
        }

        var expired = new AtomicInteger();
        var stale = new AtomicInteger();
        var subExpired = client.eventBus().subscribe(SdkTypedEvent.WatchCursorExpired.class, e -> {
            expired.incrementAndGet();
            events.add("WatchCursorExpired@" + e.timestamp() + " occ=" + e.consecutiveOccurrences());
        });
        var subStale = client.eventBus().subscribe(SdkTypedEvent.WatchStreamStale.class, e -> {
            stale.incrementAndGet();
            events.add("WatchStreamStale@" + e.timestamp() + " idleFor=" + e.idleFor());
        });

        ToxiproxyClient tp;
        try {
            tp = new ToxiproxyClient(props.toxiproxy().host(), props.toxiproxy().port());
            tp.version();
        } catch (Exception e) {
            subExpired.unsubscribe(); subStale.unsubscribe();
            return new ResilienceResult("R2", "SKIPPED", System.currentTimeMillis() - t0,
                    "Watch cursor expiry via toxiproxy stall",
                    Map.of(), Map.of(), List.copyOf(events),
                    "toxiproxy unreachable: " + e.getMessage());
        }

        // SDK round-robins across all 3 SpiceDB targets, so stall ALL proxies —
        // stalling just one doesn't catch the Watch stream if it happens to be
        // on a different node.
        var toxics = new java.util.ArrayList<eu.rekawek.toxiproxy.model.Toxic>();
        try {
            for (int n = 1; n <= 3; n++) {
                var proxy = tp.getProxy("spicedb-" + n);
                toxics.add(proxy.toxics().bandwidth("stall-r2", ToxicDirection.DOWNSTREAM, 0));
            }
            Thread.sleep(120_000);
        } finally {
            for (var t : toxics) {
                try { t.remove(); } catch (Exception ignored) { /* best-effort */ }
            }
        }

        subExpired.unsubscribe();
        subStale.unsubscribe();

        boolean ok = expired.get() >= 1 || stale.get() >= 1;
        return new ResilienceResult(
                "R2", ok ? "PASS" : "FAIL", System.currentTimeMillis() - t0,
                "Watch cursor expiry (with stream-stale fallback)",
                Map.of("toxic", "bandwidth=0", "durationSec", 120, "proxies", "spicedb-1,2,3 (all)"),
                Map.of("cursorExpired", expired.get(), "streamStale", stale.get()),
                List.copyOf(events),
                ok ? null : "expected at least 1 WatchCursorExpired or WatchStreamStale event");
    }
}
