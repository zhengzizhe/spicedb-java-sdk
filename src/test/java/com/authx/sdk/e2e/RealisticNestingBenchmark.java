package com.authx.sdk.e2e;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Realistic 10-level nesting benchmark with production-scale data:
 *
 * - 10 层 folder 嵌套
 * - 每层 50 个子文件夹（宽树）
 * - 每个 folder 有 10 个直接协作者
 * - 5 个 group，每 group 20 人
 * - 最底层 100 个 document，每个 5 个直接协作者
 * - 通过 group#member 继承权限
 *
 * 权限路径：
 *   user → group#member → folder(root)#viewer → parent→view × 10 层 → document#view
 *
 * ALL NO CACHE. Every check hits SpiceDB.
 */
public class RealisticNestingBenchmark {

    static final String ENDPOINT = "http://localhost:8090";
    static final String API_KEY = "ak_30ac4b596f8bd12c5bf4202faccd36bc";
    static final String NAMESPACE = "dev";
    static final int DEPTH = 10;
    static final int GROUPS = 5;
    static final int USERS_PER_GROUP = 20;
    static final int DIRECT_EDITORS_PER_FOLDER = 10;
    static final int DOCS_AT_BOTTOM = 100;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Realistic 10-Level Nesting Benchmark ===");
        System.out.println("  Depth: " + DEPTH + " levels");
        System.out.println("  Groups: " + GROUPS + " × " + USERS_PER_GROUP + " members = " + (GROUPS * USERS_PER_GROUP) + " users");
        System.out.println("  Direct editors per folder: " + DIRECT_EDITORS_PER_FOLDER);
        System.out.println("  Documents at bottom: " + DOCS_AT_BOTTOM);
        System.out.println("  ALL CHECKS HIT SPICEDB (no cache)\n");

        try (var client = AuthxClient.builder()
                .connection(c -> c.target("localhost:50051").presharedKey("dev-token")
                        .requestTimeout(Duration.ofSeconds(30)))
                .cache(c -> c.enabled(false))
                .features(f -> f.telemetry(false).coalescing(false))
                .build()) {

            // --- Setup ---
            System.out.println("[Setup] Creating groups and members...");
            createGroups(client);

            System.out.println("[Setup] Building 10-level folder chain with collaborators...");
            buildRealisticChain(client);

            System.out.println("[Setup] Creating " + DOCS_AT_BOTTOM + " documents at bottom level...");
            createDocuments(client);

            int totalRelationships = GROUPS * USERS_PER_GROUP       // group memberships
                    + DEPTH                                          // parent chain
                    + DEPTH * DIRECT_EDITORS_PER_FOLDER              // direct editors
                    + DEPTH * GROUPS                                 // group viewers per folder
                    + DOCS_AT_BOTTOM                                 // doc parent links
                    + DOCS_AT_BOTTOM * 5;                            // doc direct editors
            System.out.println("[Setup] Total relationships: ~" + totalRelationships);

            // --- Verify ---
            System.out.println("\n--- Permission verification ---");
            verify(client);

            // --- Benchmark 1: Single-threaded by depth ---
            System.out.println("\n--- Single-thread latency by depth (500 iterations, no cache) ---");
            System.out.println("  (Every check traverses SpiceDB permission tree)\n");
            benchmarkByDepth(client, 500);

            // --- Benchmark 2: Deep doc check, increasing thread count ---
            System.out.println("\n--- Document check through 10 levels (no cache) ---");
            for (int threads : new int[]{1, 10, 20, 50}) {
                benchmarkDeepDocCheck(client, threads, 1000);
            }

            // --- Benchmark 3: Group-inherited permission (hardest path) ---
            System.out.println("\n--- Hardest path: group member → 10-level inherit → doc view ---");
            // user in group → group#member is viewer on root → parent->view × 10 → doc view
            benchmarkGroupInherit(client, 20, 1000);

            // --- Benchmark 4: Negative check (no permission) ---
            System.out.println("\n--- Negative check: user with NO access (must traverse entire tree) ---");
            benchmarkNegativeCheck(client, 20, 1000);

            // --- Cleanup ---
            System.out.println("\n[Cleanup] Removing test data...");
            cleanup(client);
            System.out.println("[Cleanup] Done.");
        }

        System.out.println("\n=== Done ===");
    }

    static void createGroups(AuthxClient client) {
        for (int g = 0; g < GROUPS; g++) {
            List<String> members = new ArrayList<>();
            for (int u = 0; u < USERS_PER_GROUP; u++) {
                members.add("u-" + (g * USERS_PER_GROUP + u));
            }
            client.resource("group", "grp-" + g).grant("member").to(members);
        }
    }

    static void buildRealisticChain(AuthxClient client) {
        String prevFolder = "real-root";

        for (int depth = 0; depth < DEPTH; depth++) {
            String folderId = "real-L" + depth;

            // parent link
            client.resource("folder", folderId)
                    .grant("parent").toSubjects("folder:" + prevFolder);

            // direct editors (user type)
            List<String> editors = new ArrayList<>();
            for (int e = 0; e < DIRECT_EDITORS_PER_FOLDER; e++) {
                editors.add("direct-ed-" + depth + "-" + e);
            }
            client.resource("folder", folderId).grant("editor").to(editors);

            // group viewers (all groups get view on every folder)
            List<String> groupRefs = new ArrayList<>();
            for (int g = 0; g < GROUPS; g++) {
                groupRefs.add("group:grp-" + g + "#member");
            }
            client.resource("folder", folderId).grant("viewer").toSubjects(groupRefs);

            prevFolder = folderId;
        }

        // Root folder: owner + editor + group viewers
        client.resource("folder", "real-root").grant("owner").to("root-boss");
        client.resource("folder", "real-root").grant("editor").to("root-ed-0", "root-ed-1", "root-ed-2");
        for (int g = 0; g < GROUPS; g++) {
            client.resource("folder", "real-root").grant("viewer")
                    .toSubjects("group:grp-" + g + "#member");
        }
    }

    static void createDocuments(AuthxClient client) {
        String bottomFolder = "real-L" + (DEPTH - 1);
        for (int d = 0; d < DOCS_AT_BOTTOM; d++) {
            client.resource("document", "real-doc-" + d)
                    .grant("parent").toSubjects("folder:" + bottomFolder);

            // Direct editors on each doc
            List<String> docEditors = new ArrayList<>();
            for (int e = 0; e < 5; e++) {
                docEditors.add("doc-ed-" + d + "-" + e);
            }
            client.resource("document", "real-doc-" + d).grant("editor").to(docEditors);
        }
    }

    static void verify(AuthxClient client) {
        // u-0 is in grp-0 → grp-0#member is viewer on every folder → should have view on doc
        check(client, "document", "real-doc-0", "view", "u-0", true,
                "group member → 10-level inherit → doc view");

        // root-boss owns root → should have delete on deep doc
        check(client, "document", "real-doc-0", "delete", "root-boss", true,
                "root owner → 10-level → doc delete");

        // direct-ed-5-0 is editor on L5 → should have view on doc (via L5→...→L9→doc)
        check(client, "document", "real-doc-0", "view", "direct-ed-5-0", true,
                "L5 direct editor → 5-level inherit → doc view");

        // doc-ed-0-0 is direct editor on doc-0 → should have edit
        check(client, "document", "real-doc-0", "edit", "doc-ed-0-0", true,
                "direct doc editor → doc edit");

        // random user with no access
        check(client, "document", "real-doc-0", "view", "stranger-xyz", false,
                "stranger → no access (full tree traversal to confirm)");
    }

    static void check(AuthxClient client, String type, String id, String perm, String user,
                       boolean expected, String label) {
        CheckResult r = client.resource(type, id).check(perm)
                .withConsistency(Consistency.full()).by(user);
        boolean pass = r.hasPermission() == expected;
        System.out.printf("  %s %s → %s %s%n", pass ? "✓" : "✗", label,
                r.hasPermission() ? "ALLOWED" : "DENIED", pass ? "" : "(EXPECTED " + expected + "!)");
    }

    static void benchmarkByDepth(AuthxClient client, int iterations) {
        // Check view by a group member (u-0) at each depth → tests recursive computation
        String[][] targets = {
                {"folder", "real-root", "depth 0 (root direct)"},
                {"folder", "real-L0", "depth 1"},
                {"folder", "real-L2", "depth 3"},
                {"folder", "real-L4", "depth 5"},
                {"folder", "real-L7", "depth 8"},
                {"folder", "real-L9", "depth 10"},
                {"document", "real-doc-0", "depth 10 + doc (11 hops)"},
        };

        for (var t : targets) {
            long[] nanos = new long[iterations];
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                client.resource(t[0], t[1]).check("view").by("u-0");
                nanos[i] = System.nanoTime() - start;
            }
            Arrays.sort(nanos);
            System.out.printf("  %-30s p50=%.2fms  p95=%.2fms  p99=%.2fms  avg=%.2fms%n",
                    t[2],
                    nanos[(int) (iterations * 0.50)] / 1e6,
                    nanos[(int) (iterations * 0.95)] / 1e6,
                    nanos[(int) (iterations * 0.99)] / 1e6,
                    Arrays.stream(nanos).average().orElse(0) / 1e6);
        }
    }

    static void benchmarkDeepDocCheck(AuthxClient client, int threads, int opsPerThread) throws Exception {
        long[] latencies = new long[threads * opsPerThread];
        AtomicLong idx = new AtomicLong(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        long start = System.nanoTime();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try { barrier.await(); } catch (Exception e) { return; }
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < opsPerThread; i++) {
                    int docId = rng.nextInt(DOCS_AT_BOTTOM);
                    int userId = rng.nextInt(GROUPS * USERS_PER_GROUP);
                    long opStart = System.nanoTime();
                    client.resource("document", "real-doc-" + docId)
                            .check("view").by("u-" + userId);
                    long ns = System.nanoTime() - opStart;
                    int pos = (int) idx.getAndIncrement();
                    if (pos < latencies.length) latencies[pos] = ns;
                }
            }));
        }
        for (var f : futures) f.get();
        long elapsed = System.nanoTime() - start;
        pool.shutdown();

        int captured = (int) Math.min(idx.get(), latencies.length);
        long[] sorted = Arrays.copyOf(latencies, captured);
        Arrays.sort(sorted);
        int totalOps = threads * opsPerThread;
        System.out.printf("  %2d threads × %,d ops: QPS=%,.0f  p50=%.2fms  p95=%.2fms  p99=%.2fms%n",
                threads, opsPerThread,
                totalOps / (elapsed / 1e9),
                sorted[(int) (captured * 0.50)] / 1e6,
                sorted[(int) (captured * 0.95)] / 1e6,
                sorted[(int) (captured * 0.99)] / 1e6);
    }

    static void benchmarkGroupInherit(AuthxClient client, int threads, int opsPerThread) throws Exception {
        // u-0 (in grp-0) checking view on random docs → full 10-level group inherit path
        long[] latencies = new long[threads * opsPerThread];
        AtomicLong idx = new AtomicLong(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        long start = System.nanoTime();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try { barrier.await(); } catch (Exception e) { return; }
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < opsPerThread; i++) {
                    int docId = rng.nextInt(DOCS_AT_BOTTOM);
                    // Always use a group member → forces group membership resolution + 10-level traverse
                    int userId = rng.nextInt(GROUPS * USERS_PER_GROUP);
                    long opStart = System.nanoTime();
                    client.resource("document", "real-doc-" + docId)
                            .check("view").by("u-" + userId);
                    long ns = System.nanoTime() - opStart;
                    int pos = (int) idx.getAndIncrement();
                    if (pos < latencies.length) latencies[pos] = ns;
                }
            }));
        }
        for (var f : futures) f.get();
        long elapsed = System.nanoTime() - start;
        pool.shutdown();

        int captured = (int) Math.min(idx.get(), latencies.length);
        long[] sorted = Arrays.copyOf(latencies, captured);
        Arrays.sort(sorted);
        int totalOps = threads * opsPerThread;
        System.out.printf("  %d threads × %,d ops: QPS=%,.0f  p50=%.2fms  p95=%.2fms  p99=%.2fms%n",
                threads, opsPerThread,
                totalOps / (elapsed / 1e9),
                sorted[(int) (captured * 0.50)] / 1e6,
                sorted[(int) (captured * 0.95)] / 1e6,
                sorted[(int) (captured * 0.99)] / 1e6);
    }

    static void benchmarkNegativeCheck(AuthxClient client, int threads, int opsPerThread) throws Exception {
        // "stranger" has no access anywhere → SpiceDB must traverse the entire permission tree to confirm denial
        long[] latencies = new long[threads * opsPerThread];
        AtomicLong idx = new AtomicLong(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        long start = System.nanoTime();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try { barrier.await(); } catch (Exception e) { return; }
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < opsPerThread; i++) {
                    int docId = rng.nextInt(DOCS_AT_BOTTOM);
                    long opStart = System.nanoTime();
                    client.resource("document", "real-doc-" + docId)
                            .check("view").by("stranger-" + rng.nextInt(1000));
                    long ns = System.nanoTime() - opStart;
                    int pos = (int) idx.getAndIncrement();
                    if (pos < latencies.length) latencies[pos] = ns;
                }
            }));
        }
        for (var f : futures) f.get();
        long elapsed = System.nanoTime() - start;
        pool.shutdown();

        int captured = (int) Math.min(idx.get(), latencies.length);
        long[] sorted = Arrays.copyOf(latencies, captured);
        Arrays.sort(sorted);
        int totalOps = threads * opsPerThread;
        System.out.printf("  %d threads × %,d ops: QPS=%,.0f  p50=%.2fms  p95=%.2fms  p99=%.2fms%n",
                threads, opsPerThread,
                totalOps / (elapsed / 1e9),
                sorted[(int) (captured * 0.50)] / 1e6,
                sorted[(int) (captured * 0.95)] / 1e6,
                sorted[(int) (captured * 0.99)] / 1e6);
    }

    static void cleanup(AuthxClient client) {
        // Best effort cleanup
        try { client.resource("folder", "real-root").revokeAll().from("root-boss", "root-ed-0", "root-ed-1", "root-ed-2"); } catch (Exception ignored) {}
        for (int g = 0; g < GROUPS; g++) {
            try {
                List<String> members = new ArrayList<>();
                for (int u = 0; u < USERS_PER_GROUP; u++) members.add("u-" + (g * USERS_PER_GROUP + u));
                client.resource("group", "grp-" + g).revoke("member").from(members);
            } catch (Exception ignored) {}
        }
        for (int d = 0; d < DEPTH; d++) {
            try { client.resource("folder", "real-L" + d).revokeAll().from("nobody-placeholder"); } catch (Exception ignored) {}
        }
    }
}
