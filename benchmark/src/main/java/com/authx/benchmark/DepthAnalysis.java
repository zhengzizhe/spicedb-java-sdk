package com.authx.benchmark;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.CheckResult;

import java.time.Duration;
import java.util.*;

/**
 * 按权限深度/广度分析 SpiceDB 的 check 延迟。
 * 单线程串行，去除 SDK 缓存，纯测 SpiceDB 解析性能。
 */
public class DepthAnalysis {

    static final int WARMUP = 500;
    static final int MEASURE = 3000;

    public static void main(String[] args) throws Exception {
        var client = AuthxClient.builder()
                .connection(c -> c.target("localhost:50051").presharedKey("testkey")
                        .requestTimeout(Duration.ofSeconds(5)))
                .cache(c -> c.enabled(false).watchInvalidation(false))  // 无缓存
                .features(f -> f.coalescing(false))                     // 无合并
                .build();

        // 确认数据存在
        var r = client.checkResult("document", "doc-0", "view", "user-0");
        System.out.println("连接确认: doc-0#view@user-0 = " + r.permissionship());

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║      SpiceDB 权限解析延迟 — 按深度/广度分析                     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.printf("  每场景: 热身 %d + 测量 %d 次 | 无缓存 | 单线程 | full consistency%n%n", WARMUP, MEASURE);

        // ============================================================
        //  场景 1: 直接授权 — depth=0
        //  document#owner → manage (直接匹配 relation)
        //  图遍历: document.owner → 1 次查询
        // ============================================================
        measure(client, "① 直接授权 (depth=0)",
                "document#owner→manage",
                "document:doc-0 → 直接查 owner relation",
                "document", "doc-0", "manage", "user-0");

        // ============================================================
        //  场景 2: 1 层权限继承 — depth=1
        //  document#viewer → view
        //  view = comment + viewer + link_viewer + folder->view
        //  只走 viewer 分支, 1 层
        // ============================================================
        measure(client, "② 1 层权限继承 (depth=1)",
                "document#viewer→view",
                "view = comment ∪ viewer ∪ link_viewer ∪ folder→view",
                "document", "doc-0", "view", "user-500");

        // ============================================================
        //  场景 3: 权限链 depth=3 (view→comment→edit→manage)
        //  user 是 document owner, check view
        //  view → comment → edit → manage → owner ✓
        // ============================================================
        measure(client, "③ 权限链 (depth=3, 同实体内)",
                "document#owner→manage→edit→comment→view",
                "view→comment→edit→manage→owner (同一 document 内 4 层解析)",
                "document", "doc-0", "view", "user-0");

        // ============================================================
        //  场景 4: 跨实体继承 depth=2
        //  user 是 folder editor → document edit (通过 folder→edit)
        //  doc → folder → editor 匹配
        // ============================================================
        // doc-0 在 folder-0, folder-0 有 editor user-(0*7+u)%2000
        int fEditorId = (0 * 7 + 0) % 2000;
        measure(client, "④ 跨实体 (depth=2, doc→folder)",
                "folder#editor→doc.folder→edit→view",
                "doc-0.folder→folder-0.editor→user-" + fEditorId,
                "document", "doc-0", "view", "user-" + fEditorId);

        // ============================================================
        //  场景 5: 深层跨实体 depth=4+ (doc→folder→space→org)
        //  user 通过 space member 继承 document view
        //  doc → folder → space → member 匹配
        // ============================================================
        // doc-5 在 folder-5, folder-5 在 space-5, space-5 有 member user-(5*50+0)%2000=250
        measure(client, "⑤ 深层跨实体 (depth=4+, doc→folder→space)",
                "space#member→space.edit→folder.space→edit→doc.folder→edit→view",
                "doc-5→folder-5→space-5→member",
                "document", "doc-5", "view", "user-250");

        // ============================================================
        //  场景 6: 嵌套文件夹 (folder.parent→) + 空间
        //  25% 文件夹有 parent: folder-f where f>10 && f%4==0, parent=folder-(f/4)
        //  folder-12 parent=folder-3, folder-3 在 space-3
        //  doc-12 在 folder-12 → parent folder-3 → space-3
        // ============================================================
        // space-3 有 member user-(3*50+0)%2000=150
        measure(client, "⑥ 嵌套文件夹 (depth=5+, doc→folder→parent→space)",
                "doc→folder→parent→space→member",
                "doc-12→folder-12→parent:folder-3→space-3→member",
                "document", "doc-12", "view", "user-150");

        // ============================================================
        //  场景 7: 无权限（全图遍历后返回 NO_PERMISSION）
        //  这是最慢的场景 — SpiceDB 必须探索所有分支确认没有匹配
        // ============================================================
        measure(client, "⑦ 无权限（全图遍历，worst case）",
                "NO_PERMISSION — 遍历所有分支",
                "SpiceDB 必须探索完整图才能确认无权限",
                "document", "doc-2500", "view", "user-1999");

        // ============================================================
        //  场景 8: 并发下的延迟（50 线程同时 check 不同文档）
        // ============================================================
        System.out.println("── ⑧ 并发延迟 (50 线程 × 不同文档) ────────────────");
        System.out.println("  场景: 50 个线程同时 check 不同文档的 view 权限");
        {
            int threads = 50;
            int opsPerThread = 200;
            long[][] allLatencies = new long[threads][];
            var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            var futures = new ArrayList<java.util.concurrent.Future<long[]>>();

            // 热身
            for (int t = 0; t < threads; t++) {
                int docId = t * 100;
                client.check("document", "doc-" + (docId % 5000), "view", "user-0");
            }

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    long[] lats = new long[opsPerThread];
                    for (int i = 0; i < opsPerThread; i++) {
                        int docId = (threadId * 100 + i) % 5000;
                        long start = System.nanoTime();
                        client.check("document", "doc-" + docId, "view", "user-0");
                        lats[i] = System.nanoTime() - start;
                    }
                    return lats;
                }));
            }

            long[] combined = new long[threads * opsPerThread];
            int idx = 0;
            for (var f : futures) {
                long[] lats = f.get();
                System.arraycopy(lats, 0, combined, idx, lats.length);
                idx += lats.length;
            }
            pool.shutdown();

            Arrays.sort(combined);
            int n = combined.length;
            System.out.printf("  avg=%8.0fµs  p50=%8.0fµs  p95=%8.0fµs  p99=%8.0fµs  max=%8.0fµs%n",
                    Arrays.stream(combined).average().orElse(0) / 1000.0,
                    combined[(int)(n*0.50)] / 1000.0,
                    combined[(int)(n*0.95)] / 1000.0,
                    combined[(int)(n*0.99)] / 1000.0,
                    combined[n-1] / 1000.0);
        }

        // ============================================================
        //  场景 9: 并发 200 线程
        // ============================================================
        System.out.println("── ⑨ 并发延迟 (200 线程 × 不同文档) ───────────────");
        {
            int threads = 200;
            int opsPerThread = 200;
            var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            var futures = new ArrayList<java.util.concurrent.Future<long[]>>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    long[] lats = new long[opsPerThread];
                    for (int i = 0; i < opsPerThread; i++) {
                        int docId = (threadId * 25 + i) % 5000;
                        long start = System.nanoTime();
                        client.check("document", "doc-" + docId, "view", "user-" + (i % 2000));
                        lats[i] = System.nanoTime() - start;
                    }
                    return lats;
                }));
            }

            long[] combined = new long[threads * opsPerThread];
            int idx = 0;
            for (var f : futures) {
                long[] lats = f.get();
                System.arraycopy(lats, 0, combined, idx, lats.length);
                idx += lats.length;
            }
            pool.shutdown();

            Arrays.sort(combined);
            int n = combined.length;
            System.out.printf("  avg=%8.0fµs  p50=%8.0fµs  p95=%8.0fµs  p99=%8.0fµs  max=%8.0fµs%n",
                    Arrays.stream(combined).average().orElse(0) / 1000.0,
                    combined[(int)(n*0.50)] / 1000.0,
                    combined[(int)(n*0.95)] / 1000.0,
                    combined[(int)(n*0.99)] / 1000.0,
                    combined[n-1] / 1000.0);
        }

        client.close();
        System.out.println("\n完成。");
    }

    static void measure(AuthxClient client, String title, String path, String desc,
                         String type, String id, String perm, String user) {
        System.out.println("── " + title + " ────────────────");
        System.out.println("  路径: " + path);
        System.out.println("  说明: " + desc);

        // 确认结果
        var result = client.on(type).resource(id).check(perm)
                .withConsistency(com.authx.sdk.model.Consistency.full()).by(user);

        // 热身
        for (int i = 0; i < WARMUP; i++) {
            client.check(type, id, perm, user,
                    com.authx.sdk.model.Consistency.full());
        }

        // 测量 (用 full consistency 绕过 SpiceDB 内部缓存)
        long[] latencies = new long[MEASURE];
        for (int i = 0; i < MEASURE; i++) {
            long start = System.nanoTime();
            client.check(type, id, perm, user,
                    com.authx.sdk.model.Consistency.full());
            latencies[i] = System.nanoTime() - start;
        }
        Arrays.sort(latencies);
        int n = latencies.length;

        double avgUs = Arrays.stream(latencies).average().orElse(0) / 1000.0;
        double p50 = latencies[(int)(n*0.50)] / 1000.0;
        double p95 = latencies[(int)(n*0.95)] / 1000.0;
        double p99 = latencies[(int)(n*0.99)] / 1000.0;

        System.out.printf("  结果: %s | avg=%,.0fµs  p50=%,.0fµs  p95=%,.0fµs  p99=%,.0fµs%n%n",
                result.permissionship(), avgUs, p50, p95, p99);
    }
}
