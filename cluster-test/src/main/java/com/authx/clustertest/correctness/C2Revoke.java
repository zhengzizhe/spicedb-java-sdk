package com.authx.clustertest.correctness;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;

import java.util.UUID;
import java.util.function.Function;

/** C2: grant then revoke → check returns false. */
public class C2Revoke implements Function<AuthxClient, String> {
    @Override
    public String apply(AuthxClient client) {
        String docId  = "c2-doc-"  + UUID.randomUUID();
        String userId = "c2-user-" + UUID.randomUUID();

        client.on("document").resource(docId).grant("editor").to(userId);
        client.on("document").resource(docId).revoke("editor").from(userId);

        boolean ok = client.on("document").resource(docId)
                .check("edit")
                .withConsistency(Consistency.full())
                .by(userId)
                .hasPermission();

        return !ok ? null : "expected edit=false after revoke";
    }
}
