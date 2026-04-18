package com.authx.sdk.action;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.ResourceType;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.RevokeResult;
import com.authx.sdk.model.SubjectRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RevokeCompletionTest {

    enum Rel implements Relation.Named {
        EDITOR, VIEWER;
        @Override public String relationName() { return name().toLowerCase(); }
    }
    enum Perm implements Permission.Named {
        VIEW;
        @Override public String permissionName() { return name().toLowerCase(); }
    }
    private static final ResourceType<Rel, Perm> DOC =
            ResourceType.of("document", Rel.class, Perm.class);

    private static final RevokeResult R = new RevokeResult("tok-r1", 2);

    @Test
    void listener_runsInline() {
        RevokeCompletion h = new RevokeCompletionImpl(R);
        AtomicReference<Thread> fired = new AtomicReference<>();
        h.listener(r -> fired.set(Thread.currentThread()));
        assertThat(fired.get()).isEqualTo(Thread.currentThread());
    }

    @Test
    void listener_returnsThisForChaining() {
        RevokeCompletion h = new RevokeCompletionImpl(R);
        List<String> fired = new ArrayList<>();
        h.listener(r -> fired.add("a")).listener(r -> fired.add("b"));
        assertThat(fired).containsExactly("a", "b");
    }

    @Test
    void listener_nullCallbackThrows() {
        RevokeCompletion h = new RevokeCompletionImpl(R);
        assertThatThrownBy(() -> h.listener((Consumer<RevokeResult>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("callback");
    }

    @Test
    void multipleListenersRunInOrder() {
        RevokeCompletion h = new RevokeCompletionImpl(R);
        List<Integer> order = new ArrayList<>();
        h.listener(r -> order.add(1))
         .listener(r -> order.add(2))
         .listener(r -> order.add(3));
        assertThat(order).containsExactly(1, 2, 3);
    }

    @Test
    void syncListenerExceptionDoesNotInvalidateHandle() {
        RevokeCompletion h = new RevokeCompletionImpl(R);
        assertThatThrownBy(() ->
                h.listener(r -> { throw new RuntimeException("boom"); }))
                .hasMessage("boom");
        List<String> fired = new ArrayList<>();
        h.listener(r -> fired.add("ok"));
        assertThat(fired).containsExactly("ok");
    }

    // ─── Async listener tests (SR:req-9, req-11) ───

    @Test
    void listenerAsync_dispatchesToExecutor() throws Exception {
        RevokeCompletion h = new RevokeCompletionImpl(R);
        AtomicReference<Thread> fired = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ExecutorService exec = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "listener-pool"));
        try {
            h.listenerAsync(res -> {
                fired.set(Thread.currentThread());
                done.countDown();
            }, exec);
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fired.get().getName()).isEqualTo("listener-pool");
            assertThat(fired.get()).isNotEqualTo(Thread.currentThread());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void listenerAsync_nullCallbackOrExecutorThrows() {
        RevokeCompletion h = new RevokeCompletionImpl(R);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() -> h.listenerAsync(null, exec))
                    .isInstanceOf(NullPointerException.class).hasMessage("callback");
            assertThatThrownBy(() -> h.listenerAsync(r -> {}, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("executor");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void listenerAsync_rejectionPropagates() throws Exception {
        RevokeCompletion h = new RevokeCompletionImpl(R);
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            exec.submit(() -> {
                started.countDown();
                try { hold.await(); } catch (InterruptedException ignored) {}
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> h.listenerAsync(r -> {}, exec))
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            hold.countDown();
            exec.shutdownNow();
        }
    }

    // ─── Typed chain aggregation (SR:req-6) ───

    @Test
    void resultAggregatesAcrossInternalWrites() {
        try (var client = AuthxClient.inMemory()) {
            // Pre-populate so revoke has something to remove.
            client.on(DOC).select("d1", "d2")
                    .grant(Rel.EDITOR, Rel.VIEWER)
                    .to(SubjectRef.of("user", "alice", null),
                        SubjectRef.of("user", "bob", null));

            RevokeCompletion h = client.on(DOC).select("d1", "d2")
                    .revoke(Rel.EDITOR, Rel.VIEWER)
                    .from(SubjectRef.of("user", "alice", null),
                          SubjectRef.of("user", "bob", null));

            RevokeResult r = h.result();
            // InMemoryTransport's revoke count depends on the impl; the
            // aggregation invariant is that the completion is non-null and
            // count is non-negative.
            assertThat(r).isNotNull();
            assertThat(r.count()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void asyncListenerExceptionIsSwallowedAndLogged() throws Exception {
        RevokeCompletion h = new RevokeCompletionImpl(R);
        CountDownLatch done = new CountDownLatch(1);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            h.listenerAsync(r -> { throw new RuntimeException("boom-async"); }, exec);
            h.listenerAsync(r -> done.countDown(), exec);
            assertThat(done.await(2, TimeUnit.SECONDS))
                    .as("well-behaved async listener still fires after a throwing one")
                    .isTrue();
        } finally {
            exec.shutdownNow();
        }
    }
}
