package com.authcses.sdk.builtin;

import com.authcses.sdk.spi.SdkInterceptor;

/**
 * Debug interceptor: logs every operation with full details.
 * Enable with {@code .addInterceptor(new DebugInterceptor())} or {@code .debug(true)}.
 *
 * <pre>
 * → CHECK document:doc-123 permission=view subject=user:alice
 * ← CHECK 2ms result=SUCCESS
 * </pre>
 */
public class DebugInterceptor implements SdkInterceptor {

    private static final System.Logger LOG = System.getLogger("authcses.sdk.debug");

    @Override
    public void before(OperationContext ctx) {
        LOG.log(System.Logger.Level.INFO,
                "→ {0} {1}:{2} permission={3} subject={4}:{5}",
                ctx.action(), ctx.resourceType(), ctx.resourceId(),
                ctx.permission(), ctx.subjectType(), ctx.subjectId());
    }

    @Override
    public void after(OperationContext ctx) {
        if (ctx.hasError()) {
            LOG.log(System.Logger.Level.WARNING,
                    "← {0} {1}ms ERROR: {2}",
                    ctx.action(), ctx.durationMs(), ctx.error().getMessage());
        } else {
            LOG.log(System.Logger.Level.INFO,
                    "← {0} {1}ms result={2}",
                    ctx.action(), ctx.durationMs(), ctx.result());
        }
    }
}
