package com.authx.sdk.transport;

import com.authx.sdk.spi.AttributeKey;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.spi.SdkInterceptor.OperationChain;
import com.authx.sdk.spi.SdkInterceptor.OperationContext;
import com.authx.sdk.trace.LogCtx;
import com.authx.sdk.trace.LogFields;

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

    private static final System.Logger LOG = System.getLogger(RealOperationChain.class.getName());

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
        // SR:C8 — isolate interceptor exceptions on generic read operations
        // (lookup/expand/read), same contract as RealCheckChain: skip the
        // broken interceptor, log at WARNING, continue the chain.
        var interceptor = interceptors.get(index);
        try {
            return interceptor.interceptOperation(next);
        } catch (com.authx.sdk.exception.AuthxException authx) {
            throw authx;
        } catch (RuntimeException bug) {
            String subjectRef = (ctx.subjectType() == null || ctx.subjectType().isEmpty()) ? null
                    : (ctx.subjectId() == null || ctx.subjectId().isEmpty()
                            ? ctx.subjectType()
                            : ctx.subjectType() + ":" + ctx.subjectId());
            LOG.log(System.Logger.Level.WARNING, LogCtx.fmt(
                    "Read-path interceptor {0} threw {1}; skipping and continuing the chain."
                            + LogFields.suffixPerm(ctx.resourceType(), ctx.resourceId(),
                                    ctx.permission(), subjectRef),
                    interceptor.getClass().getName(), bug.toString()));
            return next.proceed();
        }
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
