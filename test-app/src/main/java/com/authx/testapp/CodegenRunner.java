package com.authx.testapp;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.AuthxCodegen;

/**
 * One-shot: connect to SpiceDB, read schema, generate type-safe enums.
 *
 * Run: ./gradlew :test-app:run -PmainClass=com.authx.testapp.CodegenRunner
 */
public class CodegenRunner {
    public static void main(String[] args) throws Exception {
        String target = System.getenv().getOrDefault("SPICEDB_TARGET", "127.0.0.1:50051");
        String key = System.getenv().getOrDefault("SPICEDB_PRESHARED_KEY", "testkey");

        try (var client = AuthxClient.builder()
                .connection(c -> c.target(target).presharedKey(key))
                .build()) {

            AuthxCodegen.generate(
                    client,
                    "src/main/java",
                    "com.authx.testapp.schema");
        }
    }
}
