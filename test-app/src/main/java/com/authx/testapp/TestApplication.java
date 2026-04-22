package com.authx.testapp;

import com.authx.sdk.AuthxClient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Minimal Spring Boot entry point for the AuthX test-app demo.
 *
 * <p>Exposes a single {@link AuthxClient} bean backed by
 * {@link AuthxClient#inMemory()} — no SpiceDB connection required, writes
 * and reads happen against the SDK's in-memory transport. See
 * {@link com.authx.testapp.demo.ProductLaunchDemo} for the end-to-end
 * usage walk-through.
 */
@SpringBootApplication
public class TestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }

    @Bean
    public AuthxClient authxClient() {
        return AuthxClient.inMemory();
    }
}
