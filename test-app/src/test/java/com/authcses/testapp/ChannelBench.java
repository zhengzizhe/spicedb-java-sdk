package com.authcses.testapp;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.sdk.model.Consistency;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 验证 gRPC 连接数对 QPS 的影响。
 * 开 1/2/4/8 个独立 AuthCsesClient，每个独立 gRPC channel，并行打同一个 SpiceDB。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChannelBench {

    @Test @Order(1)
    void multiChannelCheck() throws Exception {
        System.out.println("=== gRPC 多连接 check QPS 测试 ===");

        // 先用 1 个 client 灌数据
        var seed = buildClient();
        for (int d = 0; d < 500; d++) {
            seed.on("document").grant("ch-d" + d, "viewer", "ch-u" + (d % 100));
        }
        seed.close();

        for (int channels : new int[]{1, 2, 4, 8}) {
            // 创建 N 个独立 client（每个有独立 gRPC channel）
            List<AuthCsesClient> clients = new ArrayList<>();
            for (int i = 0; i < channels; i++) {
                clients.add(buildClient());
            }

            int totalOps = 50_000;
            int concurrency = 200;
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            Semaphore sem = new Semaphore(concurrency);
            LongAdder ops = new LongAdder();
            CountDownLatch latch = new CountDownLatch(totalOps);

            long start = System.nanoTime();
            for (int i = 0; i < totalOps; i++) {
                AuthCsesClient c = clients.get(i % channels);
                String did = "ch-d" + (i % 500);
                String uid = "ch-u" + (i % 100);
                sem.acquire();
                pool.submit(() -> {
                    try {
                        c.check("document", did, "view", uid, Consistency.full());
                        ops.increment();
                    } catch (Exception ignored) {}
                    finally { sem.release(); latch.countDown(); }
                });
            }
            latch.await(5, TimeUnit.MINUTES);
            pool.shutdown();

            double elapsed = (System.nanoTime() - start) / 1e9;
            double qps = ops.sum() / elapsed;
            System.out.printf("  channels=%-2d  QPS=%,.0f  (%.1fx vs 1ch)%n",
                    channels, qps, channels == 1 ? 1.0 : qps);

            for (var c : clients) c.close();
        }
    }

    @Test @Order(2)
    void multiChannelWrite() throws Exception {
        System.out.println("\n=== gRPC 多连接 write QPS 测试 ===");

        for (int channels : new int[]{1, 2, 4, 8}) {
            List<AuthCsesClient> clients = new ArrayList<>();
            for (int i = 0; i < channels; i++) {
                clients.add(buildClient());
            }

            int totalOps = 10_000;
            int concurrency = 200;
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            Semaphore sem = new Semaphore(concurrency);
            LongAdder ops = new LongAdder();
            LongAdder errors = new LongAdder();
            CountDownLatch latch = new CountDownLatch(totalOps);
            Random rng = new Random(42);

            long start = System.nanoTime();
            for (int i = 0; i < totalOps; i++) {
                AuthCsesClient c = clients.get(i % channels);
                String did = "mcw-d" + rng.nextInt(5000);
                String uid = "mcw-u" + rng.nextInt(50000);
                sem.acquire();
                pool.submit(() -> {
                    try {
                        c.on("document").grant(did, "viewer", uid);
                        ops.increment();
                    } catch (Exception e) { errors.increment(); }
                    finally { sem.release(); latch.countDown(); }
                });
            }
            latch.await(5, TimeUnit.MINUTES);
            pool.shutdown();

            double elapsed = (System.nanoTime() - start) / 1e9;
            double qps = ops.sum() / elapsed;
            System.out.printf("  channels=%-2d  QPS=%,.0f  errors=%d%n", channels, qps, errors.sum());

            for (var c : clients) c.close();
        }
    }

    private AuthCsesClient buildClient() {
        return AuthCsesClient.builder()
                .connection(c -> c.target("localhost:50051").presharedKey("testkey")
                        .requestTimeout(Duration.ofSeconds(30)))
                .cache(c -> c.enabled(false).watchInvalidation(false))
                .build();
    }
}
