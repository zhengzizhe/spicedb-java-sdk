package com.authx.sdk.transport;

import com.authx.sdk.trace.TraceContext;
import io.grpc.*;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;

/**
 * gRPC ClientInterceptor that propagates W3C traceparent header from OTel context.
 * Registered once on the channel -- no per-call object allocation.
 */
class TraceParentInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> TRACEPARENT_KEY =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String traceparent = TraceContext.traceparent();
                if (traceparent != null) {
                    headers.put(TRACEPARENT_KEY, traceparent);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
