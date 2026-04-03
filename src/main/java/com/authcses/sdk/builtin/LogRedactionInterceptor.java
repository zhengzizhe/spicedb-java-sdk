package com.authcses.sdk.builtin;

// Utility class — not an interceptor despite the name (V1 legacy naming)

/**
 * Redacts sensitive data (API keys, preshared keys) from log output.
 * Registered automatically when debug mode is enabled.
 */
public final class LogRedactionInterceptor {


    /** Mask a sensitive string: "ak_30ac4b596f8bd12c" → "ak_30ac****" */
    public static String mask(String sensitive) {
        if (sensitive == null) return "null";
        if (sensitive.length() <= 8) return "****";
        return sensitive.substring(0, Math.min(8, sensitive.length())) + "****";
    }

    /** Mask a preshared key: show nothing. */
    public static String maskKey(String key) {
        return key == null ? "null" : "[REDACTED]";
    }
}
