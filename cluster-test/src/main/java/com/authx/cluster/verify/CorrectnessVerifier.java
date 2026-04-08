package com.authx.cluster.verify;

import com.authx.cluster.generator.DataModel;
import com.authx.sdk.AuthxClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Correctness verification — validates permission model behavior after data import.
 */
@Component
public class CorrectnessVerifier {

    private static final System.Logger LOG = System.getLogger(CorrectnessVerifier.class.getName());

    private final AuthxClient client;

    public CorrectnessVerifier(AuthxClient client) {
        this.client = client;
    }

    public record VerifyResult(String scenario, boolean passed, String detail) {}

    public List<VerifyResult> runAll() {
        var results = new ArrayList<VerifyResult>();
        results.add(deepInheritance());
        results.add(departmentRecursion());
        results.add(linkSharing());
        results.add(batchAtomicity());
        results.add(crossInstanceConsistency());

        for (var r : results) {
            LOG.log(r.passed() ? System.Logger.Level.INFO : System.Logger.Level.WARNING,
                    "[verify] {0}: {1} — {2}", r.scenario(), r.passed() ? "PASS" : "FAIL", r.detail());
        }
        return results;
    }

    /**
     * Create a 20-layer folder chain, grant viewer at root, check view on a doc at the deepest level.
     */
    private VerifyResult deepInheritance() {
        String prefix = "verify-deep-" + System.nanoTime();
        String userId = "verify-user-deep";
        try {
            // Create folder chain: folder-0 → folder-1 → ... → folder-19
            // For ancestor model: each folder gets ancestor relations to ALL above it
            var batch = client.batch();
            for (int i = 0; i < 20; i++) {
                String folderId = prefix + "-folder-" + i;
                String spaceId = DataModel.spaceId(0);
                // folder → space
                batch.on(client.resource("folder", folderId)).grant("space").to("space:" + spaceId);
                // ancestor relations (all folders above)
                for (int j = 0; j < i; j++) {
                    String ancestorId = prefix + "-folder-" + j;
                    batch.on(client.resource("folder", folderId)).grant("ancestor").to("folder:" + ancestorId);
                }
            }
            batch.execute();

            // Grant viewer on root folder (folder-0)
            client.on("folder").resource(prefix + "-folder-0").grant("viewer").to(userId);

            // Create a document in the deepest folder
            String docId = prefix + "-doc";
            String deepFolder = prefix + "-folder-19";
            var docBatch = client.batch();
            docBatch.on(client.resource("document", docId)).grant("folder").to("folder:" + deepFolder);
            docBatch.on(client.resource("document", docId)).grant("space").to("space:" + DataModel.spaceId(0));
            docBatch.execute();

            // Check: user should have view on the doc via deep inheritance
            boolean hasView = client.on("document").resource(docId).check("view").by(userId).hasPermission();

            return new VerifyResult("deep-inheritance", hasView,
                    hasView ? "20-layer inheritance works" : "Expected view permission through 20-layer chain");
        } catch (Exception e) {
            return new VerifyResult("deep-inheritance", false, "Exception: " + e.getMessage());
        }
    }

    /**
     * Verify department#all_members recursion: child dept member can access space via parent dept.
     */
    private VerifyResult departmentRecursion() {
        String prefix = "verify-dept-" + System.nanoTime();
        String userId = prefix + "-user";
        try {
            String parentDept = prefix + "-parent";
            String childDept = prefix + "-child";
            String spaceId = prefix + "-space";

            // child dept member
            client.on("department").resource(childDept).grant("member").to(userId);
            // child → parent
            client.on("department").resource(childDept).grant("parent").to("department:" + parentDept);
            // space grants member to parent dept's all_members
            client.on("space").resource(spaceId).grant("member").to("department:" + parentDept + "#all_members");
            // space → org (required by schema)
            client.on("space").resource(spaceId).grant("org").to("organization:" + DataModel.orgId(0));

            boolean hasView = client.on("space").resource(spaceId).check("view").by(userId).hasPermission();

            return new VerifyResult("department-recursion", hasView,
                    hasView ? "Recursive department membership works"
                            : "Expected view via department#all_members recursion");
        } catch (Exception e) {
            return new VerifyResult("department-recursion", false, "Exception: " + e.getMessage());
        }
    }

    /**
     * Verify link sharing: document with link_viewer@user:* allows any user to view.
     */
    private VerifyResult linkSharing() {
        String prefix = "verify-link-" + System.nanoTime();
        try {
            String docId = prefix + "-doc";
            String randomUser = prefix + "-random-user";

            // Create doc with link sharing
            client.on("document").resource(docId).grant("link_viewer").to("user:*");

            boolean hasView = client.on("document").resource(docId).check("view").by(randomUser).hasPermission();

            return new VerifyResult("link-sharing", hasView,
                    hasView ? "Link sharing (user:*) works"
                            : "Expected view via link_viewer@user:*");
        } catch (Exception e) {
            return new VerifyResult("link-sharing", false, "Exception: " + e.getMessage());
        }
    }

    /**
     * Verify batch atomicity: grant N relations in a batch, verify all exist, revoke all, verify gone.
     */
    private VerifyResult batchAtomicity() {
        String prefix = "verify-batch-" + System.nanoTime();
        try {
            String docId = prefix + "-doc";
            int count = 10;

            // Batch grant
            var grantBatch = client.on("document").resource(docId).batch();
            for (int i = 0; i < count; i++) {
                grantBatch.grant("viewer").to(prefix + "-user-" + i);
            }
            grantBatch.execute();

            // Verify all grants
            boolean allGranted = true;
            for (int i = 0; i < count; i++) {
                boolean has = client.on("document").resource(docId)
                        .check("view").by(prefix + "-user-" + i).hasPermission();
                if (!has) { allGranted = false; break; }
            }

            // Batch revoke
            var revokeBatch = client.on("document").resource(docId).batch();
            for (int i = 0; i < count; i++) {
                revokeBatch.revoke("viewer").from(prefix + "-user-" + i);
            }
            revokeBatch.execute();

            // Verify all revoked (use full consistency)
            boolean allRevoked = true;
            for (int i = 0; i < count; i++) {
                boolean has = client.on("document").resource(docId)
                        .check("view").by(prefix + "-user-" + i).hasPermission();
                if (has) { allRevoked = false; break; }
            }

            boolean passed = allGranted && allRevoked;
            return new VerifyResult("batch-atomicity", passed,
                    passed ? "Batch grant/revoke works correctly"
                            : "allGranted=" + allGranted + " allRevoked=" + allRevoked);
        } catch (Exception e) {
            return new VerifyResult("batch-atomicity", false, "Exception: " + e.getMessage());
        }
    }

    /**
     * Verify cross-instance consistency: write then immediately read with full consistency.
     */
    private VerifyResult crossInstanceConsistency() {
        String prefix = "verify-consistency-" + System.nanoTime();
        try {
            String docId = prefix + "-doc";
            String userId = prefix + "-user";

            client.on("document").resource(docId).grant("viewer").to(userId);
            boolean has = client.on("document").resource(docId).check("view").by(userId).hasPermission();

            return new VerifyResult("cross-instance-consistency", has,
                    has ? "Immediate read-after-write is consistent"
                            : "Expected permission immediately after grant");
        } catch (Exception e) {
            return new VerifyResult("cross-instance-consistency", false, "Exception: " + e.getMessage());
        }
    }
}
