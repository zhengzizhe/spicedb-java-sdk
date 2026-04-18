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
 * <p>This wrapper attaches the rpc call to a caller-supplied
 * {@link Context.CancellableContext} so that:
 *
 * <ul>
 *   <li>{@link #close()} can issue {@code RST_STREAM} immediately, telling
 *       SpiceDB to stop sending and freeing the channel slot.</li>
 *   <li>Upstream cancellation (from a parent {@code Context}) propagates into
 *       the lazy iteration — {@link #hasNext()} / {@link #next()} re-attach
 *       the context so any Context-sensitive gRPC machinery triggered during
 *       streaming sees the same (cancellable, possibly deadline-bounded)
 *       context as the initial call start (SR:C1).</li>
 * </ul>
 *
 * <p>Standard {@code try-with-resources} handles both the happy path and
 * exceptions inside the loop body.
 */
public final class CloseableGrpcIterator<T> implements Iterator<T>, AutoCloseable {

    private final Iterator<T> delegate;
    private final Context.CancellableContext ctx;

    /**
     * Issue an rpc call inside a fresh cancellable context inherited from
     * {@link Context#current()} and wrap the resulting iterator.
     *
     * <p>Backward-compatible convenience: no deadline is attached — the
     * effective deadline is whatever the upstream context (if any) already
     * carries plus whatever the gRPC stub's {@code withDeadlineAfter(...)}
     * sets. {@link GrpcTransport} uses {@link #from(Supplier, Context.CancellableContext)}
     * directly so it can compute the effective deadline.
     */
    public static <T> CloseableGrpcIterator<T> from(Supplier<Iterator<T>> rpcCall) {
        return from(rpcCall, Context.current().withCancellation());
    }

    /**
     * Issue an rpc call inside a caller-supplied cancellable context and wrap
     * the resulting iterator. The supplier runs while {@code ctx} is the
     * thread-current context, so the underlying ClientCall inherits it. The
     * context stays alive (attached again on each {@code hasNext} / {@code next})
     * until {@link #close()} cancels it.
     */
    public static <T> CloseableGrpcIterator<T> from(Supplier<Iterator<T>> rpcCall,
                                                     Context.CancellableContext ctx) {
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
        // Re-attach ctx during lazy iteration so any Context-sensitive
        // operation triggered inside the delegate sees the same (cancellable,
        // deadline-bounded) context as the initial call start. Without this,
        // a concurrent upstream cancellation during a blocking hasNext() would
        // not observably propagate via Context machinery (it still propagates
        // via the ClientCall listener, but explicit is safer; SR:C1).
        Context prev = ctx.attach();
        try {
            return delegate.hasNext();
        } finally {
            ctx.detach(prev);
        }
    }

    @Override
    public T next() {
        Context prev = ctx.attach();
        try {
            return delegate.next();
        } finally {
            ctx.detach(prev);
        }
    }

    @Override
    public void close() {
        // null cause = normal client-side cancellation, not an error
        ctx.cancel(null);
    }
}
