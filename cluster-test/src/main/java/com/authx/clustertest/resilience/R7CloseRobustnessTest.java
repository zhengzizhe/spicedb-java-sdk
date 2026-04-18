package com.authx.clustertest.resilience;

import com.authx.clustertest.config.ClusterProps;
import com.authx.sdk.AuthxClient;
import com.authx.sdk.event.SdkTypedEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * R7 — Close robustness. Build a fresh {@link AuthxClient}, attach a listener
 * to {@link SdkTypedEvent.ClientStopping} that throws, call {@code close()},
 * and verify it returns within 10 seconds without propagating the throw.
 */
@Component
public class R7CloseRobustnessTest {
    private final ClusterProps props;

    public R7CloseRobustnessTest(ClusterProps p) { this.props = p; }

    public ResilienceResult run() {
        long t0 = System.currentTimeMillis();
        var events = Collections.synchronizedList(new ArrayList<String>());
        var addrs = props.spicedb().targets().split(",");

        AuthxClient client = AuthxClient.builder()
                .connection(c -> c
                        .targets(addrs)
                        .presharedKey(props.spicedb().presharedKey())
                        .requestTimeout(Duration.ofSeconds(10)))
                .features(f -> f
                        .virtualThreads(true)
                        .shutdownHook(false))
                .build();

        // listener that always throws on ClientStopping
        client.eventBus().subscribe(SdkTypedEvent.ClientStopping.class, e -> {
            events.add("listener-invoked-ClientStopping@" + e.timestamp());
            throw new RuntimeException("R7 intentional listener throw");
        });

        var closeThrew = new AtomicBoolean(false);
        String closeError = null;
        long closeStart = System.currentTimeMillis();
        long closeDur = -1;
        Thread t = new Thread(() -> {
            try {
                client.close();
            } catch (Throwable th) {
                closeThrew.set(true);
                events.add("close-threw " + th.getClass().getSimpleName() + ": " + th.getMessage());
            }
        }, "r7-close");
        t.start();
        try {
            t.join(10_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        closeDur = System.currentTimeMillis() - closeStart;
        boolean returnedInTime = !t.isAlive();
        if (!returnedInTime) {
            closeError = "close() did not return within 10s";
            t.interrupt();
        }

        boolean ok = returnedInTime && !closeThrew.get();
        return new ResilienceResult(
                "R7", ok ? "PASS" : "FAIL", System.currentTimeMillis() - t0,
                "close() robustness in the face of throwing listeners",
                Map.of("listenerThrows", true, "timeoutMs", 10_000),
                Map.of(
                        "closeDurationMs", closeDur,
                        "closeReturnedInTime", returnedInTime,
                        "closeThrew", closeThrew.get()),
                List.copyOf(events),
                ok ? null : (closeError != null ? closeError
                        : "close() propagated an exception"));
    }
}
