package com.authcses.sdk.spi;

import com.authcses.sdk.model.CheckRequest;
import com.authcses.sdk.model.CheckResult;
import com.authcses.sdk.model.GrantResult;
import com.authcses.sdk.model.WriteRequest;
import com.authcses.sdk.model.enums.SdkAction;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Interceptor for SDK operations using OkHttp-style chain pattern.
 *
 * <pre>
 * client = AuthCsesClient.builder()
 *     .extend(e -> e.addInterceptor(new SdkInterceptor() {
 *         @Override public CheckResult interceptCheck(CheckChain chain) {
 *             log.debug("→ CHECK {}", chain.request());
 *             CheckResult result = chain.proceed(chain.request());
 *             log.debug("← CHECK {}", result.permissionship());
 *             return result;
 *         }
 *     }))
 *     .build();
 * </pre>
 *
 * <p>Interceptors execute synchronously on the calling thread, in registration order.
 * Keep them fast.
 */
public interface SdkInterceptor {

    /**
     * OkHttp-style chain for check operations. Override for full control:
     * modify request, short-circuit, wrap errors, measure timing.
     *
     * <p>Default implementation passes through to the next interceptor in the chain.
     */
    default CheckResult interceptCheck(CheckChain chain) {
        return chain.proceed(chain.request());
    }

    /**
     * OkHttp-style chain for write operations. Override for full control:
     * modify request, short-circuit, wrap errors, measure timing.
     *
     * <p>Default implementation passes through to the next interceptor in the chain.
     */
    default GrantResult interceptWrite(WriteChain chain) {
        return chain.proceed(chain.request());
    }

    /**
     * Called before any operation that does not have a dedicated chain
     * (lookup, read, delete, expand). Override to apply cross-cutting concerns
     * such as rate limiting and bulkhead.
     *
     * <p>Default implementation is a no-op.
     */
    default void beforeOperation(OperationContext ctx) {}

    /**
     * Called after any operation that does not have a dedicated chain.
     * Always called if {@link #beforeOperation} was called, even on failure.
     *
     * <p>Default implementation is a no-op.
     */
    default void afterOperation(OperationContext ctx) {}

    // ---- Chain interfaces ----

    /**
     * Check chain — allows interceptors to modify the request, short-circuit with a result,
     * or handle errors. Modeled after OkHttp's {@code Interceptor.Chain}.
     */
    interface CheckChain {
        /** The current request (may have been modified by a previous interceptor). */
        CheckRequest request();

        /** Advance to the next interceptor, or execute the transport if at the end. */
        CheckResult proceed(CheckRequest request);

        /** Shared context for the operation. */
        OperationContext operationContext();

        /** Read a typed attribute from the context. */
        <T> T attr(AttributeKey<T> key);

        /** Set a typed attribute on the context. */
        <T> void attr(AttributeKey<T> key, T value);
    }

    /**
     * Write chain — same pattern for write operations.
     */
    interface WriteChain {
        /** The current request (may have been modified by a previous interceptor). */
        WriteRequest request();

        /** Advance to the next interceptor, or execute the transport if at the end. */
        GrantResult proceed(WriteRequest request);

        /** Shared context for the operation. */
        OperationContext operationContext();

        /** Read a typed attribute from the context. */
        <T> T attr(AttributeKey<T> key);
    }

    /**
     * Context passed to interceptors. Mutable — interceptors can add attributes.
     */
    class OperationContext {
        private final SdkAction action;
        private final String resourceType;
        private final String resourceId;
        private final String permission;
        private final String subjectType;
        private final String subjectId;
        /** Typed attribute map keyed by {@link AttributeKey} identity — no collision risk. */
        private final ConcurrentHashMap<AttributeKey<?>, Object> typedAttributes = new ConcurrentHashMap<>();

        // Set after execution
        private long durationMs;
        private String result;
        private Throwable error;

        public OperationContext(SdkAction action, String resourceType, String resourceId,
                                String permission, String subjectType, String subjectId) {
            this.action = action;
            this.resourceType = resourceType;
            this.resourceId = resourceId;
            this.permission = permission;
            this.subjectType = subjectType;
            this.subjectId = subjectId;
        }

        public SdkAction action() { return action; }
        public String resourceType() { return resourceType; }
        public String resourceId() { return resourceId; }
        public String permission() { return permission; }
        public String subjectType() { return subjectType; }
        public String subjectId() { return subjectId; }
        public long durationMs() { return durationMs; }
        public String result() { return result; }
        public Throwable error() { return error; }
        public boolean hasError() { return error != null; }

        /** Type-safe getter using {@link AttributeKey} identity. */
        @SuppressWarnings("unchecked")
        public <T> T attr(AttributeKey<T> key) {
            Object value = typedAttributes.get(key);
            if (value == null) return key.defaultValue();
            return key.type().cast(value);
        }

        /** Type-safe setter using {@link AttributeKey} identity. */
        public <T> void attr(AttributeKey<T> key, T value) {
            typedAttributes.put(key, value);
        }

        public void setDurationMs(long ms) { this.durationMs = ms; }
        public void setResult(String result) { this.result = result; }
        public void setError(Throwable error) { this.error = error; }
    }
}
