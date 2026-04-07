package com.authx.sdk.transport;

import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.WriteRequest;
import com.authx.sdk.spi.AttributeKey;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.spi.SdkInterceptor.OperationContext;
import com.authx.sdk.spi.SdkInterceptor.WriteChain;

import java.util.List;

/**
 * OkHttp-style chain implementation for write operations.
 *
 * <p>Each call to {@link #proceed(WriteRequest)} advances to the next interceptor.
 * When all interceptors are exhausted, calls the actual transport. Each chain
 * instance is immutable; proceeding creates a new chain with incremented index.
 */
public final class RealWriteChain implements WriteChain {

    private final List<SdkInterceptor> interceptors;
    private final int index;
    private final WriteRequest request;
    private final SdkTransport transport;
    private final OperationContext ctx;

    public RealWriteChain(List<SdkInterceptor> interceptors, int index,
                          WriteRequest request, SdkTransport transport, OperationContext ctx) {
        this.interceptors = interceptors;
        this.index = index;
        this.request = request;
        this.transport = transport;
        this.ctx = ctx;
    }

    @Override
    public WriteRequest request() {
        return request;
    }

    @Override
    public OperationContext operationContext() {
        return ctx;
    }

    @Override
    public GrantResult proceed(WriteRequest request) {
        if (index >= interceptors.size()) {
            // End of chain — execute the actual transport call
            return transport.writeRelationships(request.updates());
        }
        // Create next chain with incremented index and (possibly modified) request
        var next = new RealWriteChain(interceptors, index + 1, request, transport, ctx);
        return interceptors.get(index).interceptWrite(next);
    }

    @Override
    public <T> T attr(AttributeKey<T> key) {
        return ctx.attr(key);
    }
}
