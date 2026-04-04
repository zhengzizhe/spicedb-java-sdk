package com.authcses.sdk;

import com.authcses.sdk.lifecycle.LifecycleManager;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Aggregates infrastructure resources that have lifecycle (channel, scheduler, executor).
 * Mutable state (closed flag) — not a record.
 */
public final class SdkInfrastructure implements AutoCloseable {

    private final io.grpc.ManagedChannel channel;
    private final ScheduledExecutorService scheduler;
    private final Executor asyncExecutor;
    private final LifecycleManager lifecycle;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Thread shutdownHookRef;

    public SdkInfrastructure(io.grpc.ManagedChannel channel, ScheduledExecutorService scheduler,
                              Executor asyncExecutor, LifecycleManager lifecycle) {
        this.channel = channel; // nullable for InMemory usage
        this.scheduler = scheduler;
        this.asyncExecutor = asyncExecutor != null ? asyncExecutor : Runnable::run;
        this.lifecycle = lifecycle;
    }

    public io.grpc.ManagedChannel channel() { return channel; }
    public ScheduledExecutorService scheduler() { return scheduler; }
    public Executor asyncExecutor() { return asyncExecutor; }
    public LifecycleManager lifecycle() { return lifecycle; }
    public boolean isClosed() { return closed.get(); }
    public boolean markClosed() { return closed.compareAndSet(false, true); }
    public void setShutdownHook(Thread hook) { this.shutdownHookRef = hook; }
    public Thread shutdownHook() { return shutdownHookRef; }

    @Override
    public void close() {
        // Actual close logic delegated to AuthCsesClient.close() which orchestrates shutdown order
    }
}
