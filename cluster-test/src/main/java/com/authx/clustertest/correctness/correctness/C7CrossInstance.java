package com.authx.clustertest.correctness;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;

import java.util.UUID;
import java.util.function.Function;

/**
 * C7: write on this client, then immediately read with {@code Consistency.full()}.
 *
 * <p>In a cluster where the read might land on a different SpiceDB replica
 * than the write, full consistency routes to the primary and must see the
 * just-committed write.
 */
public class C7CrossInstance implements Function<AuthxClient, String> {
    @Override
    public String apply(AuthxClient client) {
        String docId  = "c7-doc-"  + UUID.randomUUID();
        String userId = "c7-user-" + UUID.randomUUID();

        client.on("document").resource(docId).grant("viewer").to(userId);

        boolean ok = client.on("document").resource(docId)
                .check("view")
                .withConsistency(Consistency.full())
                .by(userId)
                .hasPermission();

        return ok ? null : "expected view=true with Consistency.full() after write";
    }
}
