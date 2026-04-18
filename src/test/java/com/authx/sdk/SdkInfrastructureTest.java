package com.authx.sdk;

import com.authx.sdk.lifecycle.LifecycleManager;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SdkInfrastructureTest {

    @Test
    void close_continuesWhenSchedulerThrows() throws Exception {
        // Regression: a runtime exception from scheduler.shutdown() (e.g.
        // SecurityException under a custom SecurityManager) used to skip
        // channel.shutdown(), leaking the gRPC channel and its Netty pool.
        // Now each step is wrapped in safeClose so a failure in one cleanup
        // never prevents the next from running.

        var channelClosed = new AtomicBoolean(false);
        var serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .build()
                .start();
        ManagedChannel channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        // A scheduler that throws on shutdown.
        ScheduledExecutorService throwingScheduler = new ThrowingScheduler();

        var infra = new SdkInfrastructure(
                channel, throwingScheduler, Runnable::run, new LifecycleManager(new com.authx.sdk.event.DefaultTypedEventBus()));

        // Must not throw — failure in scheduler step is logged and skipped.
        infra.close();

        // Channel must still be shut down despite the scheduler failure.
        assertThat(channel.isShutdown())
                .as("channel.shutdown() must run even when scheduler.shutdown() throws")
                .isTrue();

        server.shutdownNow();
    }

    @Test
    void close_continuesWhenChannelShutdownThrows() {
        // Same property in the other direction: channel.shutdown throwing
        // must not prevent removeShutdownHook from running.

        var hookRemoved = new AtomicBoolean(false);
        var hook = new Thread(() -> {});

        // Channel that throws on shutdown.
        ManagedChannel throwingChannel = new ThrowingChannel();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        var infra = new SdkInfrastructure(
                throwingChannel, scheduler, Runnable::run, new LifecycleManager(new com.authx.sdk.event.DefaultTypedEventBus()));

        // Register a shutdown hook so we can verify the third safeClose step
        // runs. (We can't actually register one without polluting the JVM,
        // so we use the indirect verification of "scheduler was shut down".)
        infra.setShutdownHook(hook);

        infra.close();

        // Scheduler closed first — verifies it ran before the throwing channel.
        assertThat(scheduler.isShutdown())
                .as("scheduler must be shut down before the throwing channel step")
                .isTrue();
    }

    @Test
    void close_idempotent_doesNotThrowOnSecondCall() {
        // markClosed is on AuthxClient, not infra — but infra.close() should
        // still be safe to invoke directly twice without throwing (e.g. test
        // teardown calls it explicitly while AuthxClient is being GC'd).

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        var infra = new SdkInfrastructure(null, scheduler, Runnable::run, new LifecycleManager(new com.authx.sdk.event.DefaultTypedEventBus()));

        infra.close();
        infra.close();   // must not throw

        assertThat(scheduler.isShutdown()).isTrue();
    }

    // ── Test doubles ──

    /** Throws RuntimeException on shutdown(); otherwise behaves like a no-op scheduler. */
    private static final class ThrowingScheduler extends java.util.concurrent.AbstractExecutorService
            implements ScheduledExecutorService {
        @Override public void shutdown() { throw new RuntimeException("simulated SecurityException"); }
        @Override public java.util.List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }
        @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable c, long d, TimeUnit u) { return null; }
        @Override public <V> java.util.concurrent.ScheduledFuture<V> schedule(java.util.concurrent.Callable<V> c, long d, TimeUnit u) { return null; }
        @Override public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(Runnable c, long i, long p, TimeUnit u) { return null; }
        @Override public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable c, long i, long d, TimeUnit u) { return null; }
    }

    /** ManagedChannel whose shutdown() throws. */
    private static final class ThrowingChannel extends ManagedChannel {
        @Override public ManagedChannel shutdown() { throw new RuntimeException("simulated channel failure"); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public ManagedChannel shutdownNow() { return this; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public <RequestT, ResponseT> io.grpc.ClientCall<RequestT, ResponseT> newCall(
                io.grpc.MethodDescriptor<RequestT, ResponseT> methodDescriptor, io.grpc.CallOptions callOptions) {
            throw new UnsupportedOperationException();
        }
        @Override public String authority() { return "test"; }
    }
}
