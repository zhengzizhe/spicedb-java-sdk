package com.authx.sdk.internal;

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
     *
     * <p>Both the scheduler and the channel are given a 5 s graceful
     * termination window; on timeout we escalate to {@code shutdownNow()}
     * (F11-8 review fix). Leaving a channel in "shutdown but not terminated"
     * state would leak in-flight RPCs and the associated Netty resources,
     * which shows up as "threads still alive" under long-lived test apps
     * that create + destroy many clients.
     */
    @Override
    public void close() {
        // Each sub-resource is closed in its own safeClose so a throw from
        // one (e.g. SecurityException on scheduler shutdown under a custom
        // SecurityManager, or a transport bug in channel.shutdown()) cannot
        // skip the others — that would leak threads and Netty buffers.
        // Mirrors the same pattern AuthxClient.close() uses one level up.
        safeClose("scheduler.shutdown", () -> {
            if (scheduler == null) return;
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        });
        safeClose("channel.shutdown", () -> {
            if (channel == null) return;
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                    // Give the forced shutdown a brief extra window; if it
                    // still doesn't terminate, move on — we've done what we
                    // can, and blocking the caller indefinitely is worse than
                    // leaking a thread pool on JVM exit.
                    channel.awaitTermination(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        });
        safeClose("removeShutdownHook", () -> {
            if (shutdownHookRef != null && Thread.currentThread() != shutdownHookRef) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHookRef); }
                catch (IllegalStateException ignored) {}
            }
        });
    }

    /**
     * Run a sub-close, logging any throw without propagating. Mirrors the
     * AuthxClient.safeClose pattern so failures in one resource cleanup
     * never prevent the next resource from closing.
     */
    private static void safeClose(String step, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            System.getLogger(SdkInfrastructure.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "SdkInfrastructure close step failed: {0} — continuing shutdown. Error: {1}",
                    step, t.toString());
        }
    }
}
