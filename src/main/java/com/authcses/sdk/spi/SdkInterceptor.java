package com.authcses.sdk.spi;

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
        private final Map<String, Object> attributes = new java.util.concurrent.ConcurrentHashMap<>();

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

        public void setAttribute(String key, Object value) { attributes.put(key, value); }
        @SuppressWarnings("unchecked")
        public <T> T getAttribute(String key) { return (T) attributes.get(key); }

        public void setDurationMs(long ms) { this.durationMs = ms; }
        public void setResult(String result) { this.result = result; }
        public void setError(Throwable error) { this.error = error; }
    }
}
