package com.authx.clustertest.correctness;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.CrossResourceBatchBuilder;
import com.authx.sdk.model.Consistency;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * C8: batch atomicity — 100 grants commit together, all visible.
 */
public class C8BatchAtomic implements Function<AuthxClient, String> {
    private static final int N = 100;

    @Override
    public String apply(AuthxClient client) {
        String suffix = UUID.randomUUID().toString();
        String userId = "c8-user-" + suffix;

        List<String> docIds = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            docIds.add("c8-doc-" + i + "-" + suffix);
        }

        CrossResourceBatchBuilder batch = client.batch();
        CrossResourceBatchBuilder.ResourceScope scope = null;
        for (String docId : docIds) {
            scope = (scope == null ? batch.on("document", docId) : scope.on("document", docId))
                    .grant("viewer").to(userId);
        }
        if (scope == null) return "batch was empty";
        scope.commit();

        int missing = 0;
        String firstMissing = null;
        for (String docId : docIds) {
            boolean ok = client.on("document").resource(docId)
                    .check("view")
                    .withConsistency(Consistency.full())
                    .by(userId)
                    .hasPermission();
            if (!ok) {
                missing++;
                if (firstMissing == null) firstMissing = docId;
            }
        }

        return missing == 0 ? null
                : "expected all " + N + " grants visible, but " + missing
                  + " missing (first=" + firstMissing + ")";
    }
}
