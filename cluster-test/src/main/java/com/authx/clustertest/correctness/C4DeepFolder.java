package com.authx.clustertest.correctness;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;

import java.util.UUID;
import java.util.function.Function;

/**
 * C4: 20-level folder chain. Top-level viewer sees deepest document.
 *
 * <p>Schema uses ancestor-flattening: each folder writes {@code ancestor}
 * edges to every folder above it, and {@code folder.view = view_local +
 * ancestor->view_local}. We grant {@code viewer} on the top-level folder only,
 * chain 20 folders through ancestor, attach a document to the deepest folder,
 * and check {@code document.view} for the user.
 */
public class C4DeepFolder implements Function<AuthxClient, String> {
    private static final int DEPTH = 20;

    @Override
    public String apply(AuthxClient client) {
        String suffix = UUID.randomUUID().toString();
        String userId = "c4-user-" + suffix;

        String[] folders = new String[DEPTH];
        for (int i = 0; i < DEPTH; i++) {
            folders[i] = "c4-folder-" + i + "-" + suffix;
        }

        // Top-level folder: grant viewer to user directly.
        client.on("folder").resource(folders[0]).grant("viewer").to(userId);

        // For every deeper folder, write an ancestor edge to each folder above it.
        for (int i = 1; i < DEPTH; i++) {
            for (int j = 0; j < i; j++) {
                client.on("folder").resource(folders[i]).grant("ancestor")
                        .to("folder:" + folders[j]);
            }
        }

        // Attach a document to the deepest folder.
        String docId = "c4-doc-" + suffix;
        client.on("document").resource(docId).grant("folder")
                .to("folder:" + folders[DEPTH - 1]);

        boolean ok = client.on("document").resource(docId)
                .check("view")
                .withConsistency(Consistency.full())
                .by(userId)
                .hasPermission();

        return ok ? null : "expected document.view=true via " + DEPTH + "-level folder ancestor chain";
    }
}
