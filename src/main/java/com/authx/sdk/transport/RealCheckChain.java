package com.authx.sdk.transport;

import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.spi.AttributeKey;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.spi.SdkInterceptor.CheckChain;
import com.authx.sdk.spi.SdkInterceptor.OperationContext;
import com.authx.sdk.trace.LogCtx;
import com.authx.sdk.trace.LogFields;

import java.util.List;

/**
 * OkHttp-style chain implementation for check operations.
 *
 * <p>Each call to {@link #proceed(CheckRequest)} advances to the next interceptor.
 * When all interceptors are exhausted, calls the actual transport. Each chain
 * instance is immutable (index + request are fixed); proceeding creates a new chain
 * with incremented index, just like OkHttp's RealInterceptorChain.
 */
public final class RealCheckChain implements CheckChain {

    private static final System.Logger LOG = System.getLogger(RealCheckChain.class.getName());

    private final List<SdkInterceptor> interceptors;
    private final int index;
    private final CheckRequest request;
    private final SdkTransport transport;
    private final OperationContext ctx;

    public RealCheckChain(List<SdkInterceptor> interceptors, int index,
                          CheckRequest request, SdkTransport transport, OperationContext ctx) {
        this.interceptors = interceptors;
        this.index = index;
        this.request = request;
        this.transport = transport;
        this.ctx = ctx;
    }

    @Override
    public CheckRequest request() {
        return request;
    }

    @Override
    public OperationContext operationContext() {
        return ctx;
    }

    @Override
    public CheckResult proceed(CheckRequest request) {
        if (index >= interceptors.size()) {
            // End of chain — execute the actual transport call
            return transport.check(request);
        }
        // Create next chain with incremented index and (possibly modified) request
        com.authx.sdk.transport.RealCheckChain next = new RealCheckChain(interceptors, index + 1, request, transport, ctx);
        // SR:C8 — isolate read-path interceptor exceptions. An interceptor that
        // throws non-Authx (user-code bug) is logged and skipped; the chain
        // continues with `next.proceed(request)` so downstream interceptors
        // still run and the actual gRPC call still happens. This preserves
        // observability (InstrumentedTransport's finally-block telemetry) even
        // when a user-supplied read interceptor is broken.
        //
        // Authx SDK exceptions propagate unchanged — they represent genuine
        // upstream failures (auth denial, rate limit, etc.) that the caller
        // must see to handle correctly.
        com.authx.sdk.spi.SdkInterceptor interceptor = interceptors.get(index);
        try {
            return interceptor.interceptCheck(next);
        } catch (com.authx.sdk.exception.AuthxException authx) {
            throw authx;
        } catch (RuntimeException bug) {
            LOG.log(System.Logger.Level.WARNING, LogCtx.fmt(
                    "Read interceptor {0} threw {1}; skipping and continuing the chain."
                            + LogFields.suffixPerm(
                                    request.resource() == null ? null : request.resource().type(),
                                    request.resource() == null ? null : request.resource().id(),
                                    request.permission() == null ? null : request.permission().name(),
                                    request.subject() == null ? null : request.subject().toRefString()),
                    interceptor.getClass().getName(), bug.toString()));
            return next.proceed(request);
        }
    }

    @Override
    public <T> T attr(AttributeKey<T> key) {
        return ctx.attr(key);
    }

    @Override
    public <T> void attr(AttributeKey<T> key, T value) {
        ctx.attr(key, value);
    }
}
