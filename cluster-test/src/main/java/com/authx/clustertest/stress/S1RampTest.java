package com.authx.clustertest.stress;

import com.authx.clustertest.benchmark.B1ReadBenchmark;
import com.authx.clustertest.benchmark.BenchmarkResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * S1 — ramp test. Drives B1 read workload at successively higher concurrency
 * levels, 30s per step, until the breakpoint is detected (p99 > 1s or
 * error rate &gt; 5%). Returns results collected so far.
 */
@Component
public class S1RampTest {

    private static final int[] RAMP = {10, 100, 500, 1000, 2000, 5000};

    private final B1ReadBenchmark b1;

    public S1RampTest(B1ReadBenchmark b1) { this.b1 = b1; }

    public List<BenchmarkResult> run() throws InterruptedException {
        var results = new ArrayList<BenchmarkResult>();
        for (int t : RAMP) {
            var r = b1.run(t, 30_000);
            results.add(r);
            // breakpoint detection: p99 > 1s OR error rate > 5%
            if (r.p99us() > 1_000_000 || r.errors() > r.ops() * 0.05) break;
        }
        return results;
    }
}
