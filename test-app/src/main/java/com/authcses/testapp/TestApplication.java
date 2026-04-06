package com.authcses.testapp;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TestApplication {

    static {
        // Initialize OTel SDK before Spring context starts.
        // GlobalOpenTelemetry is then available to the SDK's TraceContext.
        AutoConfiguredOpenTelemetrySdk.initialize();
    }

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}
