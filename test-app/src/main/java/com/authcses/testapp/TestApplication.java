package com.authcses.testapp;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TestApplication {

    static {
        // OTel autoconfigure reads from system properties / env vars, not Spring yaml.
        // Set defaults here so traces show a meaningful service name in Jaeger.
        System.setProperty("otel.service.name",
                System.getProperty("otel.service.name", "authcses-test-app"));
        System.setProperty("otel.traces.sampler",
                System.getProperty("otel.traces.sampler", "always_on"));

        // Initialize OTel SDK before Spring context starts.
        // GlobalOpenTelemetry is then available to the SDK's TraceContext.
        AutoConfiguredOpenTelemetrySdk.initialize();
    }

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}
