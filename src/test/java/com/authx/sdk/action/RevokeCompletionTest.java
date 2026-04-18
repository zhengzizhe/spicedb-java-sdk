package com.authx.sdk.action;

import com.authx.sdk.model.RevokeResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RevokeCompletionTest {

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
}
