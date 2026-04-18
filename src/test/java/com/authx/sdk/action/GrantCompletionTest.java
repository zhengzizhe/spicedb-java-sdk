package com.authx.sdk.action;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.ResourceType;
import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
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

class GrantCompletionTest {

    // Typed test fixtures for aggregation tests.
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

    private static final GrantResult R = new GrantResult("tok-1", 3);

    @Test
    void listener_runsInline() {
        GrantCompletion h = new GrantCompletionImpl(R);
        AtomicReference<Thread> fired = new AtomicReference<>();
        h.listener(r -> fired.set(Thread.currentThread()));
        assertThat(fired.get()).isEqualTo(Thread.currentThread());
    }

    @Test
    void listener_returnsThisForChaining() {
        GrantCompletion h = new GrantCompletionImpl(R);
        List<String> fired = new ArrayList<>();
        h.listener(r -> fired.add("a"))
         .listener(r -> fired.add("b"));
        assertThat(fired).containsExactly("a", "b");
    }

    @Test
    void listener_nullCallbackThrows() {
        GrantCompletion h = new GrantCompletionImpl(R);
        assertThatThrownBy(() -> h.listener((Consumer<GrantResult>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("callback");
    }

    @Test
    void multipleListenersRunInOrder() {
        GrantCompletion h = new GrantCompletionImpl(R);
        List<Integer> order = new ArrayList<>();
        h.listener(r -> order.add(1))
         .listener(r -> order.add(2))
         .listener(r -> order.add(3));
        assertThat(order).containsExactly(1, 2, 3);
    }

    @Test
    void syncListenerExceptionDoesNotInvalidateHandle() {
        GrantCompletion h = new GrantCompletionImpl(R);
        assertThatThrownBy(() ->
                h.listener(r -> { throw new RuntimeException("boom"); }))
                .hasMessage("boom");
        // Handle still usable:
        List<String> fired = new ArrayList<>();
        h.listener(r -> fired.add("ok"));
        assertThat(fired).containsExactly("ok");
    }

    // ─── Async listener tests (SR:req-8, req-11) ───

    @Test
    void listenerAsync_dispatchesToExecutor() throws Exception {
        GrantCompletion h = new GrantCompletionImpl(R);
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
        GrantCompletion h = new GrantCompletionImpl(R);
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
        GrantCompletion h = new GrantCompletionImpl(R);
        // Saturated executor: queue size 0 + abort policy = reject when busy.
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            // Occupy the single thread so the next submit is rejected.
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

    // ─── Typed chain aggregation (SR:req-5) ───

    @Test
    void resultAggregatesAcrossInternalWrites() {
        try (var client = AuthxClient.inMemory()) {
            // 2 resources × 2 relations × 2 subjects = 4 RPCs × 2 updates each = 8 total.
            GrantCompletion h = client.on(DOC)
                    .select("d1", "d2")
                    .grant(Rel.EDITOR, Rel.VIEWER)
                    .to(SubjectRef.of("user", "alice", null),
                        SubjectRef.of("user", "bob", null));

            GrantResult r = h.result();
            // InMemoryTransport returns no zedToken; count still aggregates.
            assertThat(r.count()).isEqualTo(8);
        }
    }

    @Test
    void asyncListenerExceptionIsSwallowedAndLogged() throws Exception {
        GrantCompletion h = new GrantCompletionImpl(R);
        CountDownLatch done = new CountDownLatch(1);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            // First listener throws — impl must catch so the executor stays
            // healthy and the second listener still fires.
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
