package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;
import com.authcses.sdk.model.enums.SdkAction;
import com.authcses.sdk.spi.SdkInterceptor;
import com.authcses.sdk.spi.SdkInterceptor.OperationContext;

import java.util.List;

/**
 * Runs registered interceptors for each operation on the delegate transport.
 *
 * <p>For {@code check()} and {@code writeRelationships()}, uses OkHttp-style chains
 * ({@link RealCheckChain} / {@link RealWriteChain}) so interceptors can modify requests,
 * short-circuit, or wrap errors.
 *
 * <p>Other operations (lookup, read, expand, delete) pass through directly to the delegate.
 */
public class InterceptorTransport extends ForwardingTransport {

    private final SdkTransport delegate;
    private final List<SdkInterceptor> interceptors;

    public InterceptorTransport(SdkTransport delegate, List<SdkInterceptor> interceptors) {
        this.delegate = delegate;
        this.interceptors = List.copyOf(interceptors);
    }

    @Override
    protected SdkTransport delegate() {
        return delegate;
    }

    // ---- Chain-based operations ----

    @Override
    public CheckResult check(CheckRequest request) {
        if (interceptors.isEmpty()) return delegate.check(request);

        var ctx = buildCheckContext(request);
        var chain = new RealCheckChain(interceptors, 0, request, delegate, ctx);
        return chain.proceed(request);
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        if (interceptors.isEmpty()) return delegate.writeRelationships(updates);

        String resType = updates.isEmpty() ? "" : updates.getFirst().resource().type();
        String resId = updates.isEmpty() ? "" : updates.getFirst().resource().id();
        var ctx = new OperationContext(SdkAction.WRITE, resType, resId, "", "", "");
        var writeRequest = new WriteRequest(updates);
        var chain = new RealWriteChain(interceptors, 0, writeRequest, delegate, ctx);
        return chain.proceed(writeRequest);
    }

    @Override
    public void close() {
        delegate.close();
    }

    // ---- Internal helpers ----

    private OperationContext buildCheckContext(CheckRequest request) {
        return new OperationContext(SdkAction.CHECK,
                request.resource().type(), request.resource().id(),
                request.permission().name(),
                request.subject().type(), request.subject().id());
    }
}
