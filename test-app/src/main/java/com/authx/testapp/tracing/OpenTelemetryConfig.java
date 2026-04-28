package com.authx.testapp.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenTelemetryConfig {

    private static final System.Logger LOG = System.getLogger(OpenTelemetryConfig.class.getName());
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

    @Bean(destroyMethod = "shutdown")
    public SdkTracerProvider sdkTracerProvider(
            @Value("${authx.tracing.service-name:authx-testapp}") String serviceName,
            @Value("${authx.tracing.exporters:console}") String exporters,
            @Value("${authx.tracing.otlp.endpoint:}") String otlpEndpoint,
            @Value("${authx.tracing.otlp.timeout:5s}") Duration otlpTimeout) {
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(SERVICE_NAME, serviceName)));
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.alwaysOn()));

        String normalizedExporters = exporters == null
                ? ""
                : exporters.toLowerCase(Locale.ROOT);
        if (hasExporter(normalizedExporters, "console") || hasExporter(normalizedExporters, "logging")) {
            builder.addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()));
        }
        if (hasExporter(normalizedExporters, "otlp")) {
            builder.addSpanProcessor(BatchSpanProcessor.builder(otlpExporter(otlpEndpoint, otlpTimeout)).build());
        }

        return builder.build();
    }

    @Bean
    public OpenTelemetry openTelemetry(SdkTracerProvider tracerProvider) {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        try {
            GlobalOpenTelemetry.set(sdk);
            return sdk;
        } catch (IllegalStateException alreadyConfigured) {
            LOG.log(System.Logger.Level.WARNING,
                    "Global OpenTelemetry is already configured; test-app will use the existing instance.");
            return GlobalOpenTelemetry.get();
        }
    }

    private static boolean hasExporter(String exporters, String name) {
        if (exporters == null || exporters.isBlank()) {
            return false;
        }
        String[] parts = exporters.split(",");
        for (String part : parts) {
            String candidate = part.trim();
            if (name.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static OtlpGrpcSpanExporter otlpExporter(String endpoint, Duration timeout) {
        if (endpoint == null || endpoint.isBlank()) {
            return OtlpGrpcSpanExporter.builder()
                    .setTimeout(timeout)
                    .build();
        }
        return OtlpGrpcSpanExporter.builder()
                .setEndpoint(endpoint)
                .setTimeout(timeout)
                .build();
    }
}
