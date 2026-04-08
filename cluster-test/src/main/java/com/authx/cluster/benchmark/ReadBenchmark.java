package com.authx.cluster.benchmark;

import com.authx.cluster.generator.DataModel;
import com.authx.sdk.AuthxClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * B1 — Read benchmark: mixed check/checkAll/lookup operations.
 */
@Component
public class ReadBenchmark {

    private static final String[] PERMISSIONS = {"view", "edit", "comment", "delete", "share", "manage"};

    private final AuthxClient client;
    private final BenchmarkRunner runner;

    public ReadBenchmark(AuthxClient client, BenchmarkRunner runner) {
        this.client = client;
        this.runner = runner;
    }

    public BenchmarkResult run() {
        var docFactory = client.on("document");
        var folderFactory = client.on("folder");

        return runner.run("B1-read-mix", () -> {
            var rng = ThreadLocalRandom.current();
            int roll = rng.nextInt(100);

            if (roll < 70) {
                // 70% — single check
                String docId = DataModel.docId(rng.nextInt(DataModel.DOC_COUNT));
                String userId = DataModel.userId(rng.nextInt(DataModel.USER_COUNT));
                String perm = PERMISSIONS[rng.nextInt(PERMISSIONS.length)];
                docFactory.check(docId, perm, userId);
            } else if (roll < 80) {
                // 10% — checkAll via ResourceHandle
                String docId = DataModel.docId(rng.nextInt(DataModel.DOC_COUNT));
                String userId = DataModel.userId(rng.nextInt(DataModel.USER_COUNT));
                docFactory.resource(docId).checkAll("view", "edit", "comment").by(userId);
            } else if (roll < 90) {
                // 10% — lookup resources (who can view this doc?)
                String userId = DataModel.userId(rng.nextInt(DataModel.USER_COUNT));
                client.lookup("document").withPermission("view").by(userId).limit(10).fetch();
            } else if (roll < 95) {
                // 5% — lookup resources (folders user can view)
                String userId = DataModel.userId(rng.nextInt(DataModel.USER_COUNT));
                client.lookup("folder").withPermission("view").by(userId).limit(10).fetch();
            } else {
                // 5% — write (grant + revoke)
                String docId = DataModel.docId(rng.nextInt(DataModel.DOC_COUNT));
                String userId = DataModel.userId(rng.nextInt(DataModel.USER_COUNT));
                docFactory.grant(docId, "viewer", userId);
                docFactory.revoke(docId, "viewer", userId);
            }
        });
    }
}
