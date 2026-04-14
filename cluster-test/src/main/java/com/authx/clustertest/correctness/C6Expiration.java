package com.authx.clustertest.correctness;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

/**
 * C6: time-bounded grant. Grant with {@code expiresIn(2s)}, verify true
 * before expiry, sleep past expiry, verify false.
 */
public class C6Expiration implements Function<AuthxClient, String> {
    @Override
    public String apply(AuthxClient client) {
        String docId  = "c6-doc-"  + UUID.randomUUID();
        String userId = "c6-user-" + UUID.randomUUID();

        client.on("document").resource(docId).grant("editor")
                .expiresIn(Duration.ofSeconds(2))
                .to(userId);

        boolean before = client.on("document").resource(docId)
                .check("edit")
                .withConsistency(Consistency.full())
                .by(userId)
                .hasPermission();
        if (!before) return "expected edit=true before expiry";

        try {
            Thread.sleep(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted while waiting for expiry";
        }

        boolean after = client.on("document").resource(docId)
                .check("edit")
                .withConsistency(Consistency.full())
                .by(userId)
                .hasPermission();

        return !after ? null : "expected edit=false after expiry";
    }
}
