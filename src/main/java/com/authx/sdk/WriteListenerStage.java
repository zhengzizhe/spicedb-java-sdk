package com.authx.sdk;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * Write stage returned by {@link WriteFlow#listener(Consumer)}.
 *
 * <p>This is the final intermediate stage before commit. Its terminal
 * {@link #commit()} method returns a {@link CompletableFuture} that completes
 * after the write succeeds and the listener finishes asynchronously.
 */
public final class WriteListenerStage {

    private final WriteFlow delegate;
    private final Consumer<WriteCompletion> listener;
    private final Executor executor;

    WriteListenerStage(WriteFlow delegate, Consumer<WriteCompletion> listener) {
        this(delegate, listener, ForkJoinPool.commonPool());
    }

    WriteListenerStage(WriteFlow delegate, Consumer<WriteCompletion> listener, Executor executor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Commit the underlying write, then run the listener asynchronously.
     *
     * <p>The returned future completes with the {@link WriteCompletion} after
     * the listener returns. If the write fails or the listener throws, the
     * future completes exceptionally.
     */
    public CompletableFuture<WriteCompletion> commit() {
        final WriteCompletion completion;
        try {
            completion = delegate.commitFromListenerStage();
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
        return CompletableFuture.supplyAsync(() -> {
            listener.accept(completion);
            return completion;
        }, executor);
    }
}
