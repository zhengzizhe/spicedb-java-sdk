package com.authx.sdk;

import com.authx.sdk.event.TypedEventBus;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.spi.SdkComponents;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.telemetry.TelemetryReporter;
import com.authx.sdk.transport.CoalescingTransport;
import com.authx.sdk.transport.GrpcTransport;
import com.authx.sdk.transport.InstrumentedTransport;
import com.authx.sdk.transport.InterceptorTransport;
import com.authx.sdk.transport.PolicyAwareConsistencyTransport;
import com.authx.sdk.transport.ResilientTransport;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.TokenTracker;
import io.grpc.ManagedChannel;
import java.util.List;

/**
 * Builds the transport decoration stack.
 *
 * <p>Order is innermost to outermost:
 * {@code GrpcTransport -> ResilientTransport -> InstrumentedTransport? ->
 * PolicyAwareConsistencyTransport -> CoalescingTransport? ->
 * InterceptorTransport?}.
 */
final class TransportStackFactory {

    private TransportStackFactory() {}

    static Stack build(Request request) {
        SdkTransport transport = new GrpcTransport(
                request.grpcChannel(),
                request.presharedKey(),
                request.requestTimeoutMs());

        SdkMetrics resilientMetrics = request.telemetryEnabled() ? null : request.sdkMetrics();
        ResilientTransport resilientTransport = new ResilientTransport(
                transport,
                request.policies(),
                request.eventBus(),
                resilientMetrics);
        transport = resilientTransport;

        TelemetryReporter telemetryReporter = null;
        if (request.telemetryEnabled()) {
            telemetryReporter = new TelemetryReporter(
                    request.components().telemetrySink(),
                    request.useVirtualThreads());
            transport = new InstrumentedTransport(
                    transport,
                    telemetryReporter,
                    request.sdkMetrics());
        }

        transport = new PolicyAwareConsistencyTransport(
                transport,
                request.policies(),
                request.tokenTracker());

        if (request.coalescingEnabled()) {
            transport = new CoalescingTransport(transport, request.sdkMetrics());
        }
        if (!request.effectiveInterceptors().isEmpty()) {
            transport = new InterceptorTransport(transport, request.effectiveInterceptors());
        }

        return new Stack(transport, resilientTransport, telemetryReporter);
    }

    record Request(
            ManagedChannel grpcChannel,
            String presharedKey,
            long requestTimeoutMs,
            PolicyRegistry policies,
            SdkComponents components,
            TypedEventBus eventBus,
            SdkMetrics sdkMetrics,
            TokenTracker tokenTracker,
            List<SdkInterceptor> effectiveInterceptors,
            boolean telemetryEnabled,
            boolean coalescingEnabled,
            boolean useVirtualThreads
    ) {}

    record Stack(
            SdkTransport transport,
            ResilientTransport resilientTransport,
            TelemetryReporter telemetryReporter
    ) {}
}
