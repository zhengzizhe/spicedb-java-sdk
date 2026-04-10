package com.authx.testapp;

import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TestApplication {

    static {
        System.setProperty("otel.service.name",
                System.getProperty("otel.service.name", "authx-permission-service"));
        System.setProperty("otel.traces.sampler",
                System.getProperty("otel.traces.sampler", "always_on"));
        AutoConfiguredOpenTelemetrySdk.initialize();
    }

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}
