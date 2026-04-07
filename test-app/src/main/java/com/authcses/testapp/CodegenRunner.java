package com.authcses.testapp;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.sdk.AuthCsesCodegen;

/**
 * One-shot: connect to SpiceDB, read schema, generate type-safe enums.
 *
 * Run: ./gradlew :test-app:run -PmainClass=com.authcses.testapp.CodegenRunner
 */
public class CodegenRunner {
    public static void main(String[] args) throws Exception {
        String target = System.getenv().getOrDefault("SPICEDB_TARGET", "localhost:50061");
        String key = System.getenv().getOrDefault("SPICEDB_PRESHARED_KEY", "testkey");

        try (var client = AuthCsesClient.builder()
                .connection(c -> c.target(target).presharedKey(key))
                .build()) {

            AuthCsesCodegen.generate(
                    client,
                    "src/main/java",
                    "com.authcses.testapp.schema");
        }
    }
}
