package com.authx.sdk.transport;

import io.grpc.Context;

import java.util.Iterator;
import java.util.function.Supplier;

/**
 * AutoCloseable wrapper around a gRPC server-streaming iterator.
 *
 * <p>The blocking-stub iterators returned by gRPC Java do not implement
 * {@link AutoCloseable} and provide no way to reach
 * {@code ClientCall.cancel} — see grpc-java issues #2409 / #6727. If a
 * caller breaks out of the loop early (e.g. after collecting {@code limit}
 * results), the underlying HTTP/2 stream and Netty buffers stay alive
 * until the request deadline elapses, leaking resources on every truncated
 * read.
 *
 * <p>This wrapper attaches the rpc call to a fresh
 * {@link Context.CancellableContext} so that {@link #close()} can issue
 * {@code RST_STREAM} immediately, telling SpiceDB to stop sending and
 * freeing the channel slot. Standard {@code try-with-resources} handles
 * both the happy path and exceptions inside the loop body.
 */
public final class CloseableGrpcIterator<T> implements Iterator<T>, AutoCloseable {

    private final Iterator<T> delegate;
    private final Context.CancellableContext ctx;

    /**
     * Issue an rpc call inside a cancellable context and wrap the resulting
     * iterator. The supplier runs while the cancellable context is the
     * thread-current context, so the underlying ClientCall inherits it and
     * becomes cancellable via {@link #close()}.
     */
    public static <T> CloseableGrpcIterator<T> from(Supplier<Iterator<T>> rpcCall) {
        Context.CancellableContext ctx = Context.current().withCancellation();
        Context prev = ctx.attach();
        try {
            return new CloseableGrpcIterator<>(rpcCall.get(), ctx);
        } finally {
            ctx.detach(prev);
        }
    }

    private CloseableGrpcIterator(Iterator<T> delegate, Context.CancellableContext ctx) {
        this.delegate = delegate;
        this.ctx = ctx;
    }

    @Override
    public boolean hasNext() {
        return delegate.hasNext();
    }

    @Override
    public T next() {
        return delegate.next();
    }

    @Override
    public void close() {
        // null cause = normal client-side cancellation, not an error
        ctx.cancel(null);
    }
}
