package com.authx.benchmark;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;

import java.time.Duration;
import java.util.*;

/**
 * V2 Schema 验证 — 20 级文件夹嵌套性能测试。
 *
 * 构建: space → folder-1 → folder-2 → ... → folder-20 → document
 * 每层文件夹都有独立的协作者。
 * 验证: 通过第 1 层协作者 check 第 20 层文档的 view 权限。
 */
public class AncestorBenchmark {

    static final int MAX_DEPTH = 20;
    static final int WARMUP = 200;
    static final int MEASURE = 2000;

    public static void main(String[] args) throws Exception {
        var client = AuthxClient.builder()
                .connection(c -> c.targets("localhost:50051", "localhost:50052")
                        .presharedKey("testkey")
                        .requestTimeout(Duration.ofSeconds(10)))
                .cache(c -> c.enabled(false).watchInvalidation(false))
                .features(f -> f.coalescing(false))
                .build();

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   V2 Ancestor Schema — 20 级嵌套性能验证                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ── 1. 创建 space ──
        System.out.println("[1/4] 创建组织结构...");
        client.on("organization").resource("bench-org").grant("admin").to("org-admin");
        client.on("space").resource("bench-space").grant("org")
                .toSubjects("organization:bench-org");
        client.on("space").resource("bench-space").grant("owner").to("space-owner");
        client.on("space").resource("bench-space").grant("member").to("space-member");

        // ── 2. 创建 20 级嵌套文件夹 ──
        System.out.println("[2/4] 创建 20 级嵌套文件夹 (每层有独立协作者)...");
        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            String folderId = "deep-folder-" + depth;

            // space 引用
            client.on("folder").resource(folderId).grant("space")
                    .toSubjects("space:bench-space");

            // 写入所有祖先（V2 核心：展平 ancestor 关系）
            for (int ancestor = 1; ancestor < depth; ancestor++) {
                client.on("folder").resource(folderId).grant("ancestor")
                        .toSubjects("folder:deep-folder-" + ancestor);
            }

            // 每层文件夹独立的协作者
            client.grant("folder", folderId, "owner", "folder-owner-" + depth);
            client.grant("folder", folderId, "editor", "folder-editor-" + depth);
            client.grant("folder", folderId, "viewer", "folder-viewer-" + depth);

            System.out.printf("  folder-%d: %d ancestors, 3 collaborators%n", depth, depth - 1);
        }

        // ── 3. 创建文档在最深层文件夹 ──
        System.out.println("[3/4] 创建文档在 folder-20 中...");
        client.on("document").resource("deep-doc").grant("folder")
                .toSubjects("folder:deep-folder-" + MAX_DEPTH);
        client.on("document").resource("deep-doc").grant("space")
                .toSubjects("space:bench-space");
        client.grant("document", "deep-doc", "owner", "doc-owner");
        client.grant("document", "deep-doc", "editor", "doc-editor");
        client.grant("document", "deep-doc", "viewer", "doc-viewer");

        System.out.println();
        System.out.println("[4/4] 开始测量...");
        System.out.printf("  热身 %d 次 + 测量 %d 次 | full consistency | 单线程%n%n", WARMUP, MEASURE);

        // ── 正确性验证 ──
        System.out.println("── 正确性验证 ────────────────────────────────────────");
        verify(client, "doc-owner 直接 owner", "deep-doc", "view", "doc-owner", true);
        verify(client, "doc-viewer 直接 viewer", "deep-doc", "view", "doc-viewer", true);
        verify(client, "folder-20 editor", "deep-doc", "view", "folder-editor-20", true);
        verify(client, "folder-1 editor (20层继承)", "deep-doc", "view", "folder-editor-1", true);
        verify(client, "folder-10 viewer (10层继承)", "deep-doc", "view", "folder-viewer-10", true);
        verify(client, "space-member", "deep-doc", "view", "space-member", true);
        verify(client, "org-admin", "deep-doc", "view", "org-admin", true);
        verify(client, "无权限用户", "deep-doc", "view", "nobody", false);
        System.out.println();

        // ── 延迟测量 ──
        System.out.println("── 延迟测量 (full consistency, µs) ──────────────────");
        System.out.printf("%-45s %8s %8s %8s %8s%n", "场景", "P50", "P95", "P99", "Avg");
        System.out.println("─".repeat(85));

        bench(client, "① doc owner → manage (depth=0)", "deep-doc", "manage", "doc-owner");
        bench(client, "② doc viewer → view (depth=0)", "deep-doc", "view", "doc-viewer");
        bench(client, "③ folder-20 editor → doc view (depth=1)", "deep-doc", "view", "folder-editor-20");
        bench(client, "④ folder-10 viewer → doc view (depth=11)", "deep-doc", "view", "folder-viewer-10");
        bench(client, "⑤ folder-1 editor → doc view (depth=20)", "deep-doc", "view", "folder-editor-1");
        bench(client, "⑥ folder-1 owner → doc manage (depth=20)", "deep-doc", "manage", "folder-owner-1");
        bench(client, "⑦ space member → doc view (通过space)", "deep-doc", "view", "space-member");
        bench(client, "⑧ org admin → doc manage (通过org→space)", "deep-doc", "manage", "org-admin");
        bench(client, "⑨ 无权限 → NO_PERMISSION (全图遍历)", "deep-doc", "view", "nobody");

        // ── folder 本身的 check ──
        System.out.println();
        System.out.println("── folder 自身的 check (非 document) ────────────────");
        System.out.printf("%-45s %8s %8s %8s %8s%n", "场景", "P50", "P95", "P99", "Avg");
        System.out.println("─".repeat(85));

        bench(client, "⑩ folder-20 自身: owner → manage", "folder", "deep-folder-20", "manage", "folder-owner-20");
        bench(client, "⑪ folder-20: folder-1 editor → view (19 ancestors)", "folder", "deep-folder-20", "view", "folder-editor-1");
        bench(client, "⑫ folder-20: 无权限 (遍历 19 ancestors)", "folder", "deep-folder-20", "view", "nobody");

        // ── 并发测试 ──
        System.out.println();
        System.out.println("── 并发吞吐 (深层 check) ────────────────────────────");
        for (int threads : new int[]{1, 50, 200}) {
            long[] lats = concurrentBench(client, threads, 1000,
                    "deep-doc", "view", "folder-editor-1");
            Arrays.sort(lats);
            int n = lats.length;
            System.out.printf("  %3d 线程: p50=%,6dµs  p95=%,6dµs  p99=%,6dµs  QPS=%,.0f%n",
                    threads,
                    lats[(int)(n*0.50)] / 1000,
                    lats[(int)(n*0.95)] / 1000,
                    lats[(int)(n*0.99)] / 1000,
                    n / (Arrays.stream(lats).sum() / 1e9) * threads);
        }

        // ── CRDB 查询数量测量 ──
        System.out.println();
        System.out.println("── CRDB 查询数量 (每次 check) ──────────────────────");
        measureCrdbQueries(client, "深层 doc view (20级继承)", "deep-doc", "view", "folder-editor-1");
        measureCrdbQueries(client, "浅层 doc manage (直接 owner)", "deep-doc", "manage", "doc-owner");
        measureCrdbQueries(client, "无权限 (全图遍历)", "deep-doc", "view", "nobody");

        client.close();
        System.out.println("\n完成。");
    }

    static void verify(AuthxClient client, String label, String docId, String perm,
                        String userId, boolean expected) {
        boolean result = client.check("document", docId, perm, userId,
                Consistency.full());
        String status = result == expected ? "✓" : "✗ WRONG!";
        System.out.printf("  %s %-35s → %s (expected %s)%n", status, label,
                result ? "HAS_PERMISSION" : "NO_PERMISSION",
                expected ? "HAS_PERMISSION" : "NO_PERMISSION");
    }

    static void bench(AuthxClient client, String label, String docId, String perm, String userId) {
        bench(client, label, "document", docId, perm, userId);
    }

    static void bench(AuthxClient client, String label, String type, String id, String perm, String userId) {
        // 热身
        for (int i = 0; i < WARMUP; i++) {
            client.check(type, id, perm, userId, Consistency.full());
        }
        // 测量
        long[] lats = new long[MEASURE];
        for (int i = 0; i < MEASURE; i++) {
            long start = System.nanoTime();
            client.check(type, id, perm, userId, Consistency.full());
            lats[i] = System.nanoTime() - start;
        }
        Arrays.sort(lats);
        int n = lats.length;
        System.out.printf("%-45s %,8d %,8d %,8d %,8d%n", label,
                lats[(int)(n*0.50)] / 1000,
                lats[(int)(n*0.95)] / 1000,
                lats[(int)(n*0.99)] / 1000,
                (long)(Arrays.stream(lats).average().orElse(0) / 1000));
    }

    static long[] concurrentBench(AuthxClient client, int threads, int totalOps,
                                   String docId, String perm, String userId) throws Exception {
        int opsPerThread = totalOps / threads;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var futures = new ArrayList<java.util.concurrent.Future<long[]>>();

        // 热身
        for (int i = 0; i < 100; i++) {
            client.check("document", docId, perm, userId, Consistency.full());
        }

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                long[] l = new long[opsPerThread];
                for (int i = 0; i < opsPerThread; i++) {
                    long start = System.nanoTime();
                    client.check("document", docId, perm, userId, Consistency.full());
                    l[i] = System.nanoTime() - start;
                }
                return l;
            }));
        }

        long[] combined = new long[threads * opsPerThread];
        int idx = 0;
        for (var f : futures) {
            long[] l = f.get();
            System.arraycopy(l, 0, combined, idx, l.length);
            idx += l.length;
        }
        pool.shutdown();
        return combined;
    }

    static void measureCrdbQueries(AuthxClient client, String label,
                                    String docId, String perm, String userId) {
        try {
            String beforeStr = fetchCrdbSelectCount();
            long before = parseSciNotation(beforeStr);

            for (int i = 0; i < 100; i++) {
                client.check("document", docId, perm, userId, Consistency.full());
            }

            String afterStr = fetchCrdbSelectCount();
            long after = parseSciNotation(afterStr);
            long diff = after - before;
            System.out.printf("  %-40s %d CRDB SELECTs / 100 checks = %.1f 次/check%n",
                    label, diff, diff / 100.0);
        } catch (Exception e) {
            System.out.printf("  %-40s (无法测量: %s)%n", label, e.getMessage());
        }
    }

    static String fetchCrdbSelectCount() throws Exception {
        var pb = new ProcessBuilder("bash", "-c",
                "curl -s http://localhost:8080/_status/vars | grep '^sql_select_count{' | awk '{print $NF}'");
        var p = pb.start();
        String result = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return result;
    }

    static long parseSciNotation(String s) {
        return (long) Double.parseDouble(s);
    }
}
