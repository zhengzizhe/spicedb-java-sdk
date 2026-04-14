package com.authx.clustertest.stress;

import com.authx.clustertest.benchmark.B1ReadBenchmark;
import com.authx.clustertest.benchmark.BenchmarkResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * S2 — sustained stress. Runs B1 at the highest S1 step that held p99 &lt; 500ms,
 * for a long duration (default 5 minutes at 500 threads).
 */
@Component
public class S2SustainedTest {

    private static final long P99_BUDGET_US = 500_000L; // 500ms in microseconds
    private static final int DEFAULT_THREADS = 500;
    private static final long DEFAULT_DURATION_MS = 5 * 60 * 1000L; // 5 minutes

    private final B1ReadBenchmark b1;

    public S2SustainedTest(B1ReadBenchmark b1) { this.b1 = b1; }

    /** Run with explicit thread count & duration. */
    public BenchmarkResult run(int threads, long durationMs) throws InterruptedException {
        return b1.run(threads, durationMs);
    }

    /** Default run: 500 threads for 5 minutes. */
    public BenchmarkResult run() throws InterruptedException {
        return run(DEFAULT_THREADS, DEFAULT_DURATION_MS);
    }

    /**
     * Run by picking the highest concurrency from the given S1 results that
     * kept p99 under 500ms. Falls back to the default thread count if none qualify.
     */
    public BenchmarkResult runFromS1(List<BenchmarkResult> s1Results, long durationMs) throws InterruptedException {
        int chosen = DEFAULT_THREADS;
        if (s1Results != null) {
            for (BenchmarkResult r : s1Results) {
                if (r.p99us() < P99_BUDGET_US && r.threads() > chosen) {
                    chosen = r.threads();
                }
            }
        }
        return run(chosen, durationMs);
    }
}
