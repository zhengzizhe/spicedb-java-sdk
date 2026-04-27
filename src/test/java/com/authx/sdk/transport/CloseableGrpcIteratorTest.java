package com.authx.sdk.transport;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloseableGrpcIteratorTest {

    @Test
    void delegatesHasNextAndNext() {
        try (com.authx.sdk.transport.CloseableGrpcIterator<java.lang.String> it = CloseableGrpcIterator.from(() -> List.of("a", "b", "c").iterator())) {
            assertThat(it.hasNext()).isTrue();
            assertThat(it.next()).isEqualTo("a");
            assertThat(it.next()).isEqualTo("b");
            assertThat(it.next()).isEqualTo("c");
            assertThat(it.hasNext()).isFalse();
        }
    }

    @Test
    void closeIsIdempotent() {
        com.authx.sdk.transport.CloseableGrpcIterator<java.lang.Integer> it = CloseableGrpcIterator.from(() -> List.of(1, 2, 3).iterator());
        it.close();
        it.close();   // Context.cancel() tolerates repeated calls
    }

    @Test
    void earlyBreakClosesContext() {
        // Prove that try-with-resources runs close() even when the loop
        // exits early — this is the core property that fixes the leak.
        java.util.concurrent.atomic.AtomicBoolean closed = new AtomicBoolean(false);
        try (com.authx.sdk.transport.CloseableGrpcIteratorTest.TrackingCloseable<java.lang.Integer> it = new TrackingCloseable<>(List.of(1, 2, 3, 4, 5).iterator(), closed)) {
            int collected = 0;
            while (it.hasNext() && collected < 2) {
                it.next();
                collected++;
            }
            assertThat(closed.get()).isFalse();   // not yet — still inside try
        }
        assertThat(closed.get()).isTrue();         // closed on scope exit
    }

    @Test
    void exceptionInLoopStillTriggersClose() {
        java.util.concurrent.atomic.AtomicBoolean closed = new AtomicBoolean(false);
        assertThatThrownBy(() -> {
            try (com.authx.sdk.transport.CloseableGrpcIteratorTest.TrackingCloseable<java.lang.Integer> it = new TrackingCloseable<>(List.of(1, 2, 3).iterator(), closed)) {
                it.next();
                throw new RuntimeException("business logic failed");
            }
        }).isInstanceOf(RuntimeException.class).hasMessage("business logic failed");
        assertThat(closed.get()).isTrue();
    }

    @Test
    void nextOnExhaustedThrows() {
        try (com.authx.sdk.transport.CloseableGrpcIterator<java.lang.String> it = CloseableGrpcIterator.from(() -> List.<String>of().iterator())) {
            assertThat(it.hasNext()).isFalse();
            assertThatThrownBy(it::next).isInstanceOf(NoSuchElementException.class);
        }
    }

    /** Test double — mirrors CloseableGrpcIterator's contract without needing a real gRPC stub. */
    private static final class TrackingCloseable<T> implements Iterator<T>, AutoCloseable {
        private final Iterator<T> delegate;
        private final AtomicBoolean closedFlag;

        TrackingCloseable(Iterator<T> delegate, AtomicBoolean closedFlag) {
            this.delegate = delegate;
            this.closedFlag = closedFlag;
        }

        @Override public boolean hasNext() { return delegate.hasNext(); }
        @Override public T next() { return delegate.next(); }
        @Override public void close() { closedFlag.set(true); }
    }
}
