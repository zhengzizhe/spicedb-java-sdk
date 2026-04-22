package com.authx.sdk;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class AuthxClientBuilderSchemaTest {

    @Test
    void loadSchemaOnStartDefaultsTrue() throws Exception {
        var b = AuthxClient.builder();
        Field f = AuthxClientBuilder.class.getDeclaredField("loadSchemaOnStart");
        f.setAccessible(true);
        assertThat(f.getBoolean(b)).isTrue();
    }

    @Test
    void loadSchemaOnStartCanBeDisabled() throws Exception {
        var b = AuthxClient.builder().loadSchemaOnStart(false);
        Field f = AuthxClientBuilder.class.getDeclaredField("loadSchemaOnStart");
        f.setAccessible(true);
        assertThat(f.getBoolean(b)).isFalse();
    }

    @Test
    void inMemoryClientReportsSchemaNotLoaded() {
        try (var client = AuthxClient.inMemory()) {
            assertThat(client.schema()).isNotNull();
            assertThat(client.schema().isLoaded()).isFalse();
            assertThat(client.schema().resourceTypes()).isEmpty();
        }
    }
}
