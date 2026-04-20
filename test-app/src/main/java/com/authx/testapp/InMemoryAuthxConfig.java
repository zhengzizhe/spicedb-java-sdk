package com.authx.testapp;

import com.authx.sdk.AuthxClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * In-memory {@link AuthxClient} for demos / offline runs. Activate with
 * {@code --spring.profiles.active=inmem} or {@code SPRING_PROFILES_ACTIVE=inmem}.
 * Used by the logging & traceability demo so you don't need to run a real
 * SpiceDB cluster to see the new log format.
 *
 * <p>Note: {@link AuthxClient#inMemory()} uses {@code InMemoryTransport}
 * directly — it does NOT go through the full transport chain
 * ({@code InstrumentedTransport} + {@code InterceptorTransport} etc.), so
 * the 15-key MDC push does not fire on per-RPC basis. The logging demo in
 * {@link LogDemoController} compensates by pushing MDC and activating
 * spans explicitly inside the handler, which is exactly what a real SDK
 * RPC would do through {@code InterceptorTransport}.
 */
@Configuration
@Profile("inmem")
public class InMemoryAuthxConfig {

    @Bean(destroyMethod = "close")
    public AuthxClient authxClient() {
        return AuthxClient.inMemory();
    }
}
