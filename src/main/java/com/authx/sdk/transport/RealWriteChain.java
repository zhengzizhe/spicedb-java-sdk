package com.authx.sdk.transport;

import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.WriteRequest;
import com.authx.sdk.spi.AttributeKey;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.spi.SdkInterceptor.OperationContext;
import com.authx.sdk.spi.SdkInterceptor.WriteChain;
import com.authx.sdk.trace.LogCtx;
import com.authx.sdk.trace.LogFields;

import java.util.List;

/**
 * OkHttp-style chain implementation for write operations.
 *
 * <p>Each call to {@link #proceed(WriteRequest)} advances to the next interceptor.
 * When all interceptors are exhausted, calls the actual transport. Each chain
 * instance is immutable; proceeding creates a new chain with incremented index.
 */
public final class RealWriteChain implements WriteChain {

    private static final System.Logger LOG = System.getLogger(RealWriteChain.class.getName());

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
        com.authx.sdk.transport.RealWriteChain next = new RealWriteChain(interceptors, index + 1, request, transport, ctx);
        // SR:C8 — WRITE path is asymmetric to read paths: an interceptor that
        // throws must NOT be silently skipped. Write interceptors are often
        // doing policy enforcement (audit hooks, mandatory caveat injection,
        // forbidden-relationship guards); skipping a broken one and proceeding
        // to commit the write is a fail-open behavior that can violate
        // compliance requirements.
        //
        // Fail-closed policy: any non-Authx exception from a write interceptor
        // aborts the chain and surfaces as an AuthxException so callers see a
        // typed, retryable-classified error.
        com.authx.sdk.spi.SdkInterceptor interceptor = interceptors.get(index);
        try {
            return interceptor.interceptWrite(next);
        } catch (com.authx.sdk.exception.AuthxException authx) {
            throw authx;
        } catch (RuntimeException bug) {
            // First update carries enough resource context for the suffix —
            // batch writes can span types, but the first entry still tells the
            // on-call engineer where to start looking. Empty update list is
            // uncommon but handled.
            com.authx.sdk.transport.SdkTransport.RelationshipUpdate first = request.updates().isEmpty() ? null : request.updates().get(0);
            LOG.log(System.Logger.Level.WARNING, LogCtx.fmt(
                    "Write interceptor {0} threw {1}; aborting write (fail-closed)."
                            + LogFields.suffixRel(
                                    first == null || first.resource() == null ? null : first.resource().type(),
                                    first == null || first.resource() == null ? null : first.resource().id(),
                                    first == null || first.relation() == null ? null : first.relation().name(),
                                    first == null || first.subject() == null ? null : first.subject().toRefString()),
                    interceptor.getClass().getName(), bug.toString()));
            throw new com.authx.sdk.exception.AuthxException(
                    "Write interceptor " + interceptor.getClass().getName()
                            + " rejected the request: " + bug.getMessage(),
                    bug);
        }
    }

    @Override
    public <T> T attr(AttributeKey<T> key) {
        return ctx.attr(key);
    }
}
