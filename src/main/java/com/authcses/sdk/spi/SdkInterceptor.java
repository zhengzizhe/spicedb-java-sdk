package com.authcses.sdk.spi;

import com.authcses.sdk.model.CheckRequest;
import com.authcses.sdk.model.CheckResult;
import com.authcses.sdk.model.GrantResult;
import com.authcses.sdk.model.WriteRequest;
import com.authcses.sdk.model.enums.SdkAction;
import java.util.Map;

/**
 * Interceptor for SDK operations. Runs before/after each check, grant, revoke, lookup, etc.
 *
 * <pre>
 * client = AuthCsesClient.builder()
 *     .addInterceptor(new SdkInterceptor() {
 *         @Override public void before(OperationContext ctx) {
 *             log.debug("→ {} {}:{} by {}", ctx.action(), ctx.resourceType(), ctx.resourceId(), ctx.subjectId());
 *         }
 *         @Override public void after(OperationContext ctx) {
 *             log.debug("← {} {}ms result={}", ctx.action(), ctx.durationMs(), ctx.result());
 *         }
 *     })
 *     .build();
 * </pre>
 *
 * <p>Interceptors execute synchronously on the calling thread, in registration order (before)
 * and reverse order (after). Keep them fast.
 */
public interface SdkInterceptor {

    /**
     * Called BEFORE the operation is executed. Can inspect/modify context.
     * Throw to abort the operation.
     */
    default void before(OperationContext ctx) {}

    /**
     * Called AFTER the operation completes (success or failure).
     * Context includes duration and result/error.
     */
    default void after(OperationContext ctx) {}

    // ---- OkHttp-style chain methods ----

    /**
     * OkHttp-style chain for check operations. Override for full control:
     * modify request, short-circuit, wrap errors, measure timing.
     *
     * <p>Default implementation bridges to the legacy {@link #before}/{@link #after} pattern
     * so existing interceptors work without changes.
     */
    default CheckResult interceptCheck(CheckChain chain) {
        var ctx = chain.operationContext();
        before(ctx);
        try {
            CheckResult result = chain.proceed(chain.request());
            ctx.setResult(result.permissionship().name());
            after(ctx);
            return result;
        } catch (Exception e) {
            ctx.setError(e);
            after(ctx);
            throw e;
        }
    }

    /**
     * OkHttp-style chain for write operations. Override for full control:
     * modify request, short-circuit, wrap errors, measure timing.
     *
     * <p>Default implementation bridges to the legacy {@link #before}/{@link #after} pattern.
     */
    default GrantResult interceptWrite(WriteChain chain) {
        var ctx = chain.operationContext();
        before(ctx);
        try {
            GrantResult result = chain.proceed(chain.request());
            after(ctx);
            return result;
        } catch (Exception e) {
            ctx.setError(e);
            after(ctx);
            throw e;
        }
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
     * Context passed to interceptors. Mutable — interceptors can add attributes.
     */
    class OperationContext {
        private final SdkAction action;
        private final String resourceType;
        private final String resourceId;
        private final String permission;
        private final String subjectType;
        private final String subjectId;
        /** Legacy string-keyed attributes for backward compatibility. */
        private final Map<String, Object> attributes = new java.util.concurrent.ConcurrentHashMap<>();
        /** Typed attribute map keyed by {@link AttributeKey} identity — no collision risk. */
        private final Map<AttributeKey<?>, Object> typedAttributes = new java.util.HashMap<>();

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

        /** Legacy string-keyed setter — kept for backward compatibility. */
        public void setAttribute(String key, Object value) { attributes.put(key, value); }
        /** Legacy string-keyed getter — kept for backward compatibility. */
        @SuppressWarnings("unchecked")
        public <T> T getAttribute(String key) { return (T) attributes.get(key); }

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
