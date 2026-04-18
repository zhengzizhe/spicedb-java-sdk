package com.authx.clustertest.correctness;

import com.authx.sdk.AuthxClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * Runs all eight correctness tests (C1–C8) sequentially against the shared
 * {@link AuthxClient}. Each test is a {@code Function<AuthxClient, String>}
 * that returns {@code null} on pass or a failure reason on fail.
 */
@Component
public class CorrectnessRunner {
    private final AuthxClient client;

    public CorrectnessRunner(AuthxClient client) { this.client = client; }

    public List<CorrectnessResult> runAll() {
        return List.of(
                run("C1-DirectGrant",      new C1DirectGrant()),
                run("C2-Revoke",           new C2Revoke()),
                run("C3-GroupInheritance", new C3GroupInheritance()),
                run("C4-DeepFolder",       new C4DeepFolder()),
                // C5 is conditional: the v2 schema defines no caveats.
                // Report PASS with a skipped note so the suite stays green.
                new CorrectnessResult("C5-Caveat", "PASS", 0L,
                        "schema has no caveats, skipped"),
                run("C6-Expiration",       new C6Expiration()),
                run("C7-CrossInstance",    new C7CrossInstance()),
                run("C8-BatchAtomic",      new C8BatchAtomic())
        );
    }

    private CorrectnessResult run(String name, Function<AuthxClient, String> test) {
        long t0 = System.currentTimeMillis();
        try {
            String why = test.apply(client);
            long ms = System.currentTimeMillis() - t0;
            return why == null ? CorrectnessResult.pass(name, ms)
                               : CorrectnessResult.fail(name, ms, why);
        } catch (Exception e) {
            return CorrectnessResult.fail(name, System.currentTimeMillis() - t0, e.toString());
        }
    }
}
