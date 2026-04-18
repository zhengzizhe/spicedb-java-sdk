package com.authx.clustertest.matrix;

import com.authx.sdk.model.*;
import com.authx.sdk.transport.ForwardingTransport;
import com.authx.sdk.transport.SdkTransport;

import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/**
 * Simulates hierarchy depth cost: each check incurs a delay proportional to
 * the configured depth. Approximates what SpiceDB does when it has to walk
 * an ancestor chain (e.g. folder.view = viewer + ancestor->view). Real
 * SpiceDB+CRDB per-level cost is ~1-5ms; we use 100μs per level here so the
 * measurement stays in-memory fast but the trend is visible.
 *
 * <p>Used only for depth-dimension benchmarks.
 */
public class DepthSimTransport extends ForwardingTransport {
    private final SdkTransport delegate;
    private final int depth;
    private static final long NANOS_PER_LEVEL = 100_000L;   // 100μs per level

    public DepthSimTransport(SdkTransport delegate, int depth) {
        this.delegate = delegate;
        this.depth = depth;
    }

    @Override protected SdkTransport delegate() { return delegate; }

    @Override
    public CheckResult check(CheckRequest request) {
        if (depth > 0) {
            long deadline = System.nanoTime() + depth * NANOS_PER_LEVEL;
            while (System.nanoTime() < deadline) {
                LockSupport.parkNanos(Math.min(deadline - System.nanoTime(), 10_000));
            }
        }
        return delegate.check(request);
    }
}
