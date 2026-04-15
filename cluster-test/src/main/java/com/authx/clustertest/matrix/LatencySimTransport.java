package com.authx.clustertest.matrix;

import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.transport.ForwardingTransport;
import com.authx.sdk.transport.SdkTransport;

import java.util.concurrent.locks.LockSupport;

/**
 * Simulates realistic backend latency. Every forwarded check spends the
 * configured number of microseconds busy-waiting / parked, mimicking what
 * a real SpiceDB + CRDB round-trip costs on a typical network.
 *
 * <p>Without this, "cache miss" measurements are misleading because
 * InMemoryTransport's HashMap lookup is itself only a microsecond or two —
 * so cache provides almost no speedup. Real-world miss cost is 500μs-5ms;
 * real-world hit cost is 1-10μs. The realistic speedup is 100-1000×.
 *
 * <p>Reads, writes, lookups, and expand all pay the penalty.
 */
public class LatencySimTransport extends ForwardingTransport {
    private final SdkTransport delegate;
    private final long delayNanos;

    /** @param delayMicros artificial delay per RPC in microseconds */
    public LatencySimTransport(SdkTransport delegate, long delayMicros) {
        this.delegate = delegate;
        this.delayNanos = delayMicros * 1000L;
    }

    @Override protected SdkTransport delegate() { return delegate; }

    private void stall() {
        if (delayNanos <= 0) return;
        long deadline = System.nanoTime() + delayNanos;
        // Short sleeps use LockSupport.parkNanos for low overhead; very short
        // tail uses onSpinWait for accuracy.
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            if (remaining > 50_000) {
                LockSupport.parkNanos(remaining - 10_000);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    @Override
    public CheckResult check(CheckRequest request) {
        stall();
        return delegate.check(request);
    }
}
