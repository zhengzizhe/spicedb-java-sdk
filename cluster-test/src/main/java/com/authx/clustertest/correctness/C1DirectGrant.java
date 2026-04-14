package com.authx.clustertest.correctness;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;

import java.util.UUID;
import java.util.function.Function;

/** C1: grant editor → check edit returns true. */
public class C1DirectGrant implements Function<AuthxClient, String> {
    @Override
    public String apply(AuthxClient client) {
        String docId  = "c1-doc-"  + UUID.randomUUID();
        String userId = "c1-user-" + UUID.randomUUID();

        client.on("document").resource(docId).grant("editor").to(userId);

        boolean ok = client.on("document").resource(docId)
                .check("edit")
                .withConsistency(Consistency.full())
                .by(userId)
                .hasPermission();

        return ok ? null : "expected edit=true after grant editor";
    }
}
