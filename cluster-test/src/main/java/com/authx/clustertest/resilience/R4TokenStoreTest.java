package com.authx.clustertest.resilience;

import com.authx.clustertest.config.ClusterProps;
import com.authx.sdk.AuthxClient;
import com.authx.sdk.event.SdkTypedEvent;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ReadConsistency;
import com.authx.sdk.policy.ResourcePolicy;
import com.authx.sdk.spi.DistributedTokenStore;
import com.authx.sdk.spi.SdkComponents;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * R4 — Token store availability events. Wires a mock
 * {@link DistributedTokenStore} with a toggleable {@code failing} flag,
 * issues writes, and verifies that the SDK emits
 * {@link SdkTypedEvent.TokenStoreUnavailable} and
 * {@link SdkTypedEvent.TokenStoreRecovered} events.
 */
@Component
public class R4TokenStoreTest {
    private final ClusterProps props;

    public R4TokenStoreTest(ClusterProps p) { this.props = p; }

    /** Mock store — toggles {@code failing} to simulate Redis outage. */
    static final class MockTokenStore implements DistributedTokenStore {
        volatile boolean failing = false;
        final ConcurrentHashMap<String, String> mem = new ConcurrentHashMap<>();

        @Override public void set(String key, String token) {
            if (failing) throw new RuntimeException("mock store unavailable");
            mem.put(key, token);
        }

        @Override public String get(String key) {
            if (failing) throw new RuntimeException("mock store unavailable");
            return mem.get(key);
        }
    }

    public ResilienceResult run() {
        long t0 = System.currentTimeMillis();
        var events = Collections.synchronizedList(new ArrayList<String>());
        var mockStore = new MockTokenStore();
        var unavailable = new AtomicInteger();
        var recovered = new AtomicInteger();

        var addrs = props.spicedb().targets().split(",");
        AuthxClient client = AuthxClient.builder()
                .connection(c -> c
                        .targets(addrs)
                        .presharedKey(props.spicedb().presharedKey())
                        .requestTimeout(Duration.ofSeconds(30)))
                .features(f -> f
                        .virtualThreads(true)
                        .shutdownHook(false))
                .extend(e -> e
                        .policies(PolicyRegistry.builder()
                                .defaultPolicy(ResourcePolicy.builder()
                                        .readConsistency(ReadConsistency.session())
                                        .build())
                                .build())
                        .components(SdkComponents.builder().tokenStore(mockStore).build()))
                .build();

        try {
            var subU = client.eventBus().subscribe(SdkTypedEvent.TokenStoreUnavailable.class, e -> {
                unavailable.incrementAndGet();
                events.add("TokenStoreUnavailable@" + e.timestamp() + " reason=" + e.reason());
            });
            var subR = client.eventBus().subscribe(SdkTypedEvent.TokenStoreRecovered.class, e -> {
                recovered.incrementAndGet();
                events.add("TokenStoreRecovered@" + e.timestamp());
            });

            try {
                // Flip to failing and drive a few writes to trigger Unavailable
                mockStore.failing = true;
                driveWrites(client, "r4-fail");
                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

                // Flip back to healthy and drive more to trigger Recovered
                mockStore.failing = false;
                driveWrites(client, "r4-ok");
                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } finally {
                subU.unsubscribe();
                subR.unsubscribe();
            }

            boolean ok = unavailable.get() >= 1 && recovered.get() >= 1;
            return new ResilienceResult(
                    "R4", ok ? "PASS" : "FAIL", System.currentTimeMillis() - t0,
                    "DistributedTokenStore availability events",
                    Map.of("phases", List.of("failing=true", "failing=false")),
                    Map.of("unavailable", unavailable.get(), "recovered", recovered.get()),
                    List.copyOf(events),
                    ok ? null : "expected >=1 Unavailable and >=1 Recovered event");
        } finally {
            client.close();
        }
    }

    private static void driveWrites(AuthxClient client, String tag) {
        for (int i = 0; i < 5; i++) {
            String docId = "doc-" + tag + "-" + i;
            String user = "user-" + tag + "-" + i;
            try {
                client.on("document").grant(docId, "viewer", user);
            } catch (Exception ignored) { /* mock store failures must not throw per SPI contract */ }
            try {
                client.on("document").revoke(docId, "viewer", user);
            } catch (Exception ignored) { /* best-effort */ }
        }
    }
}
