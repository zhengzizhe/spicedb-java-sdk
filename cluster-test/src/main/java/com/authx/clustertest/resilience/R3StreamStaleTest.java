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
 * R3 — Watch stream stale detection. Stalls Watch via toxiproxy bandwidth=0
 * for 90 seconds (the SDK stale threshold is 60s), and expects at least one
 * {@link SdkTypedEvent.WatchStreamStale} event.
 */
@Component
public class R3StreamStaleTest {
    private final AuthxClient client;
    private final ClusterProps props;

    public R3StreamStaleTest(AuthxClient c, ClusterProps p) { this.client = c; this.props = p; }

    public ResilienceResult run() throws Exception {
        long t0 = System.currentTimeMillis();
        var events = Collections.synchronizedList(new ArrayList<String>());

        if (!props.toxiproxy().enabled()) {
            return new ResilienceResult("R3", "SKIPPED", System.currentTimeMillis() - t0,
                    "Watch app-layer stall detection",
                    Map.of(), Map.of(), List.of(), "toxiproxy not configured");
        }

        var staleCount = new AtomicInteger();
        var sub = client.eventBus().subscribe(SdkTypedEvent.WatchStreamStale.class, e -> {
            staleCount.incrementAndGet();
            events.add("WatchStreamStale@" + e.timestamp() + " idleFor=" + e.idleFor());
        });

        ToxiproxyClient tp;
        try {
            tp = new ToxiproxyClient(props.toxiproxy().host(), props.toxiproxy().port());
            tp.version();
        } catch (Exception e) {
            sub.unsubscribe();
            return new ResilienceResult("R3", "SKIPPED", System.currentTimeMillis() - t0,
                    "Watch app-layer stall detection",
                    Map.of(), Map.of(), List.copyOf(events),
                    "toxiproxy unreachable: " + e.getMessage());
        }

        var proxy = tp.getProxy("spicedb-1");
        var toxic = proxy.toxics().bandwidth("stall-r3", ToxicDirection.DOWNSTREAM, 0);
        try {
            Thread.sleep(90_000);
        } finally {
            try { toxic.remove(); } catch (Exception ignored) { /* best-effort */ }
        }
        sub.unsubscribe();

        boolean ok = staleCount.get() >= 1;
        return new ResilienceResult(
                "R3", ok ? "PASS" : "FAIL", System.currentTimeMillis() - t0,
                "Watch app-layer stall detection",
                Map.of("toxic", "bandwidth=0", "durationSec", 90, "proxy", "spicedb-1"),
                Map.of("staleEvents", staleCount.get()),
                List.copyOf(events),
                ok ? null : "expected at least 1 WatchStreamStale event");
    }
}
