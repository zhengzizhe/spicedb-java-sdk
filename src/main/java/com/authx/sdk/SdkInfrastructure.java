package com.authx.sdk;

import com.authx.sdk.lifecycle.LifecycleManager;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    /**
     * Shuts down scheduler, channel, and removes the shutdown hook.
     * Must be called at most once (guarded by {@link #markClosed()}).
     */
    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdown();
            try { scheduler.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (channel != null) {
            channel.shutdown();
            try { channel.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (shutdownHookRef != null && Thread.currentThread() != shutdownHookRef) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHookRef); }
            catch (IllegalStateException ignored) {}
        }
    }
}
