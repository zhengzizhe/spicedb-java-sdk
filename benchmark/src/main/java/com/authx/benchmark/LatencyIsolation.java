package com.authx.benchmark;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.GrpcTransport;
import com.authzed.api.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 延迟隔离测试 — 精确测量每一层的开销。
 *
 * Layer 1: 裸 gRPC stub（绕过 SDK）
 * Layer 2: GrpcTransport（SDK 最底层传输）
 * Layer 3: SDK 完整链（cache + coalescing + resilience + interceptor）
 *
 * 每层跑 1000 次热身 + 10000 次测量，单线程串行。
 */
public class LatencyIsolation {

    static final int WARMUP = 1000;
    static final int MEASURE = 10000;
    static final String TARGET = "localhost:50051";
    static final String PSK = "testkey";

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║         延迟隔离测试 — SDK 各层开销分析                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.printf("  热身: %,d 次 | 测量: %,d 次 | 单线程串行%n%n", WARMUP, MEASURE);

        // ── 准备数据 ──
        var channel = ManagedChannelBuilder.forTarget(TARGET).usePlaintext().build();
        Metadata authMeta = new Metadata();
        authMeta.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + PSK);

        // 确保有测试数据
        var writeStub = PermissionsServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authMeta));
        writeStub.writeRelationships(WriteRelationshipsRequest.newBuilder()
                .addUpdates(com.authzed.api.v1.RelationshipUpdate.newBuilder()
                        .setOperation(com.authzed.api.v1.RelationshipUpdate.Operation.OPERATION_TOUCH)
                        .setRelationship(Relationship.newBuilder()
                                .setResource(ObjectReference.newBuilder().setObjectType("document").setObjectId("bench-doc"))
                                .setRelation("viewer")
                                .setSubject(SubjectReference.newBuilder()
                                        .setObject(ObjectReference.newBuilder().setObjectType("user").setObjectId("bench-user")))))
                .build());

        // ================================================================
        //  Layer 1: 裸 gRPC stub
        // ================================================================
        System.out.println("── Layer 1: 裸 gRPC stub ──────────────────────────");
        {
            var stub = PermissionsServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authMeta));

            var request = CheckPermissionRequest.newBuilder()
                    .setResource(ObjectReference.newBuilder().setObjectType("document").setObjectId("bench-doc"))
                    .setPermission("view")
                    .setSubject(SubjectReference.newBuilder()
                            .setObject(ObjectReference.newBuilder().setObjectType("user").setObjectId("bench-user")))
                    .setConsistency(com.authzed.api.v1.Consistency.newBuilder().setMinimizeLatency(true))
                    .build();

            // 热身
            for (int i = 0; i < WARMUP; i++) {
                stub.withDeadlineAfter(5, TimeUnit.SECONDS).checkPermission(request);
            }

            // 测量
            long[] latencies = new long[MEASURE];
            for (int i = 0; i < MEASURE; i++) {
                long start = System.nanoTime();
                stub.withDeadlineAfter(5, TimeUnit.SECONDS).checkPermission(request);
                latencies[i] = System.nanoTime() - start;
            }
            printStats("裸 gRPC", latencies);
        }

        // ================================================================
        //  Layer 2: GrpcTransport（SDK 最底层）
        // ================================================================
        System.out.println("── Layer 2: GrpcTransport（SDK 传输层）─────────────");
        {
            var transport = new GrpcTransport(channel, PSK, 5000);
            var request = CheckRequest.of(
                    ResourceRef.of("document", "bench-doc"),
                    Permission.of("view"),
                    SubjectRef.of("user", "bench-user", null),
                    com.authx.sdk.model.Consistency.minimizeLatency());

            for (int i = 0; i < WARMUP; i++) {
                transport.check(request);
            }

            long[] latencies = new long[MEASURE];
            for (int i = 0; i < MEASURE; i++) {
                long start = System.nanoTime();
                transport.check(request);
                latencies[i] = System.nanoTime() - start;
            }
            printStats("GrpcTransport", latencies);
        }

        // ================================================================
        //  Layer 3: SDK 完整链（MinimizeLatency + cache）
        // ================================================================
        System.out.println("── Layer 3: SDK 完整链（cache + coalescing）────────");
        {
            var client = AuthxClient.builder()
                    .connection(c -> c.target(TARGET).presharedKey(PSK).requestTimeout(Duration.ofSeconds(5)))
                    .cache(c -> c.enabled(true).maxSize(200_000).watchInvalidation(false))
                    .features(f -> f.coalescing(true))
                    .extend(e -> e.policies(
                            com.authx.sdk.policy.PolicyRegistry.builder()
                                    .defaultPolicy(com.authx.sdk.policy.ResourcePolicy.builder()
                                            .cache(com.authx.sdk.policy.CachePolicy.builder()
                                                    .enabled(true).ttl(Duration.ofSeconds(30)).build())
                                            .readConsistency(com.authx.sdk.policy.ReadConsistency.minimizeLatency())
                                            .build())
                                    .build()))
                    .build();

            // 热身（填充缓存）
            for (int i = 0; i < WARMUP; i++) {
                client.check("document", "bench-doc", "view", "bench-user");
            }

            // 测量（全部缓存命中）
            long[] latencies = new long[MEASURE];
            for (int i = 0; i < MEASURE; i++) {
                long start = System.nanoTime();
                client.check("document", "bench-doc", "view", "bench-user");
                latencies[i] = System.nanoTime() - start;
            }
            printStats("SDK 完整链（缓存命中）", latencies);

            // ================================================================
            //  Layer 3b: SDK 完整链（缓存未命中 — 每次不同 key）
            // ================================================================
            System.out.println("── Layer 3b: SDK 完整链（缓存未命中）───────────────");
            // 先种一批不同的数据
            for (int i = 0; i < MEASURE; i++) {
                // 这些 check 大部分会 miss（随机 user id）
            }
            long[] missLatencies = new long[Math.min(MEASURE, 5000)];
            for (int i = 0; i < missLatencies.length; i++) {
                long start = System.nanoTime();
                client.check("document", "bench-doc", "view", "random-user-" + i);
                missLatencies[i] = System.nanoTime() - start;
            }
            printStats("SDK 完整链（缓存未命中）", missLatencies);

            System.out.println("── SDK 内部指标 ────────────────────────────────────");
            var m = client.metrics().snapshot();
            System.out.printf("  缓存命中: %,d | 未命中: %,d | 命中率: %.1f%%%n",
                    m.cacheHits(), m.cacheMisses(), m.cacheHitRate() * 100);
            System.out.printf("  合并请求: %,d%n", m.coalescedRequests());

            client.close();
        }

        // ================================================================
        //  Layer 4: 高并发吞吐测试（200 线程）
        // ================================================================
        System.out.println("── Layer 4: 高并发吞吐（200 线程 × 5 秒）──────────");
        {
            var client = AuthxClient.builder()
                    .connection(c -> c.target(TARGET).presharedKey(PSK).requestTimeout(Duration.ofSeconds(5)))
                    .cache(c -> c.enabled(true).maxSize(200_000).watchInvalidation(false))
                    .features(f -> f.coalescing(true))
                    .extend(e -> e.policies(
                            com.authx.sdk.policy.PolicyRegistry.builder()
                                    .defaultPolicy(com.authx.sdk.policy.ResourcePolicy.builder()
                                            .cache(com.authx.sdk.policy.CachePolicy.builder()
                                                    .enabled(true).ttl(Duration.ofSeconds(30)).build())
                                            .readConsistency(com.authx.sdk.policy.ReadConsistency.minimizeLatency())
                                            .build())
                                    .build()))
                    .build();

            // 热身
            for (int i = 0; i < 500; i++) {
                client.check("document", "bench-doc", "view", "user-" + (i % 100));
            }

            int threads = 200;
            int durationSec = 5;
            var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            var running = new java.util.concurrent.atomic.AtomicBoolean(true);
            var ops = new java.util.concurrent.atomic.LongAdder();
            var errs = new java.util.concurrent.atomic.LongAdder();
            long deadline = System.currentTimeMillis() + durationSec * 1000L;

            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    var rng = java.util.concurrent.ThreadLocalRandom.current();
                    while (running.get() && System.currentTimeMillis() < deadline) {
                        try {
                            // 100 个 key 循环 — 高缓存命中率
                            client.check("document", "bench-doc", "view", "user-" + rng.nextInt(100));
                            ops.increment();
                        } catch (Exception e) {
                            errs.increment();
                        }
                    }
                }));
            }
            for (var f : futures) f.get();
            running.set(false);
            pool.shutdown();

            long totalOps = ops.sum();
            double qps = totalOps / (double) durationSec;
            System.out.printf("  总操作: %,d | QPS: %,.0f | 错误: %d%n", totalOps, qps, errs.sum());
            var m = client.metrics().snapshot();
            System.out.printf("  缓存命中: %,d | 未命中: %,d | 命中率: %.1f%%%n",
                    m.cacheHits(), m.cacheMisses(), m.cacheHitRate() * 100);
            System.out.printf("  合并请求: %,d%n", m.coalescedRequests());
            client.close();
        }

        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("\n完成。");
    }

    static void printStats(String label, long[] nanos) {
        java.util.Arrays.sort(nanos);
        int n = nanos.length;
        double avgUs = 0;
        for (long l : nanos) avgUs += l;
        avgUs = avgUs / n / 1000.0;

        double p50 = nanos[(int)(n * 0.50)] / 1000.0;
        double p95 = nanos[(int)(n * 0.95)] / 1000.0;
        double p99 = nanos[(int)(n * 0.99)] / 1000.0;
        double min = nanos[0] / 1000.0;
        double max = nanos[n - 1] / 1000.0;

        System.out.printf("  %-28s  avg=%8.0fµs  p50=%8.0fµs  p95=%8.0fµs  p99=%8.0fµs  min=%6.0fµs  max=%8.0fµs%n",
                label, avgUs, p50, p95, p99, min, max);
    }
}
