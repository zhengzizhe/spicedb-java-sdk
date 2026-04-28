package com.authx.testapp.tools;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.AuthxCodegen;

public final class GenerateAuthxSchema {

    private static final String DEFAULT_TARGET = "localhost:50051";
    private static final String DEFAULT_TOKEN = "localdevlocaldevlocaldevlocaldev";
    private static final String DEFAULT_OUTPUT_DIR = "test-app/src/main/java";
    private static final String DEFAULT_PACKAGE = "com.authx.testapp.schema";

    public static void main(String[] args) throws Exception {
        String target = env("AUTHX_SPICEDB_TARGET", DEFAULT_TARGET);
        String token = env("AUTHX_SPICEDB_TOKEN", DEFAULT_TOKEN);
        String outputDir = env("AUTHX_SCHEMA_OUTPUT_DIR", DEFAULT_OUTPUT_DIR);
        String packageName = env("AUTHX_SCHEMA_PACKAGE", DEFAULT_PACKAGE);

        try (AuthxClient client = AuthxClient.builder()
                .connection(c -> c
                        .target(target)
                        .presharedKey(token)
                        .tls(false))
                .build()) {
            AuthxCodegen.generate(client, outputDir, packageName);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private GenerateAuthxSchema() {}
}
