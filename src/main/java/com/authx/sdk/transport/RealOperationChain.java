package com.authx.sdk.transport;

import com.authx.sdk.spi.AttributeKey;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.spi.SdkInterceptor.OperationChain;
import com.authx.sdk.spi.SdkInterceptor.OperationContext;

import java.util.List;
import java.util.function.Supplier;

/**
 * Generic chain implementation for operations without a dedicated request type.
 *
 * <p>Each call to {@link #proceed()} advances to the next interceptor.
 * When all interceptors are exhausted, calls the actual transport operation.
 *
 * @param <T> the return type of the operation
 */
public final class RealOperationChain<T> implements OperationChain<T> {

    private final List<SdkInterceptor> interceptors;
    private final int index;
    private final Supplier<T> terminalOperation;
    private final OperationContext ctx;

    public RealOperationChain(List<SdkInterceptor> interceptors, int index,
                              Supplier<T> terminalOperation, OperationContext ctx) {
        this.interceptors = interceptors;
        this.index = index;
        this.terminalOperation = terminalOperation;
        this.ctx = ctx;
    }

    @Override
    public T proceed() {
        if (index >= interceptors.size()) {
            return terminalOperation.get();
        }
        var next = new RealOperationChain<>(interceptors, index + 1, terminalOperation, ctx);
        return interceptors.get(index).interceptOperation(next);
    }

    @Override
    public OperationContext context() {
        return ctx;
    }

    @Override
    public <V> V attr(AttributeKey<V> key) {
        return ctx.attr(key);
    }

    @Override
    public <V> void attr(AttributeKey<V> key, V value) {
        ctx.attr(key, value);
    }
}
