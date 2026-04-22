package com.authx.sdk;

import com.authx.sdk.model.RevokeResult;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Chainable handle returned by {@link RevokeFlow#commit()}.
 * Mirror of {@link GrantCompletion}; see it for semantics.
 */
public final class RevokeCompletion {

    private static final System.Logger LOG =
            System.getLogger(RevokeCompletion.class.getName());

    private final RevokeResult result;

    RevokeCompletion(RevokeResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    public RevokeResult result() { return result; }

    public int count() { return result.count(); }

    public String zedToken() { return result.zedToken(); }

    public RevokeCompletion listener(Consumer<RevokeResult> callback) {
        Objects.requireNonNull(callback, "callback").accept(result);
        return this;
    }

    public RevokeCompletion listenerAsync(Consumer<RevokeResult> callback, Executor executor) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(executor, "executor");
        executor.execute(() -> {
            try {
                callback.accept(result);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING,
                        "RevokeCompletion async listener threw — swallowed", t);
            }
        });
        return this;
    }
}
