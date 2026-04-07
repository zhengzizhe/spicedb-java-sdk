package com.authx.sdk.spi;

import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.WriteRequest;
import com.authx.sdk.model.enums.SdkAction;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Interceptor for SDK operations using OkHttp-style chain pattern.
 *
 * <pre>
 * client = AuthxClient.builder()
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
     * Generic chain for all operations that don't have a dedicated request type
     * (lookup, read, delete, expand, bulk check). Override for full control:
     * wrap errors, measure timing, enforce rate limits.
     *
     * <p>Default implementation passes through to the next interceptor in the chain.
     *
     * <p>Replaces the previous beforeOperation/afterOperation hooks with a unified
     * chain pattern, consistent with interceptCheck and interceptWrite.
     */
    default <T> T interceptOperation(OperationChain<T> chain) {
        return chain.proceed();
    }

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
     * Generic chain for operations without a dedicated request type.
     * Interceptors can wrap the call, measure timing, enforce rate limits, etc.
     *
     * @param <T> the return type of the operation
     */
    interface OperationChain<T> {
        /** Advance to the next interceptor, or execute the transport if at the end. */
        T proceed();

        /** Shared context for the operation. */
        OperationContext context();

        /** Read a typed attribute from the context. */
        <V> V attr(AttributeKey<V> key);

        /** Set a typed attribute on the context. */
        <V> void attr(AttributeKey<V> key, V value);
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
