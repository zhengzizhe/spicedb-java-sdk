package com.authx.sdk.transport;

import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.spi.AttributeKey;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.spi.SdkInterceptor.CheckChain;
import com.authx.sdk.spi.SdkInterceptor.OperationContext;

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
        var next = new RealCheckChain(interceptors, index + 1, request, transport, ctx);
        return interceptors.get(index).interceptCheck(next);
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
