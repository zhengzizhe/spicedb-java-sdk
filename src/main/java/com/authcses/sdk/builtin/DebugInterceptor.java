package com.authcses.sdk.builtin;

import com.authcses.sdk.model.CheckResult;
import com.authcses.sdk.model.GrantResult;
import com.authcses.sdk.spi.SdkInterceptor;

/**
 * Debug interceptor: logs every operation with full details.
 * Enable with {@code .extend(e -> e.addInterceptor(new DebugInterceptor()))}.
 *
 * <pre>
 * -> CHECK document:doc-123 permission=view subject=user:alice
 * <- CHECK 2ms result=HAS_PERMISSION
 * </pre>
 */
public class DebugInterceptor implements SdkInterceptor {

    private static final System.Logger LOG = System.getLogger("authcses.sdk.debug");

    @Override
    public CheckResult interceptCheck(CheckChain chain) {
        var ctx = chain.operationContext();
        LOG.log(System.Logger.Level.INFO,
                "-> {0} {1}:{2} permission={3} subject={4}:{5}",
                ctx.action(), ctx.resourceType(), ctx.resourceId(),
                ctx.permission(), ctx.subjectType(), ctx.subjectId());
        long start = System.nanoTime();
        try {
            CheckResult result = chain.proceed(chain.request());
            long ms = (System.nanoTime() - start) / 1_000_000;
            LOG.log(System.Logger.Level.INFO,
                    "<- {0} {1}ms result={2}",
                    ctx.action(), ms, result.permissionship());
            return result;
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            LOG.log(System.Logger.Level.WARNING,
                    "<- {0} {1}ms ERROR: {2}",
                    ctx.action(), ms, e.getMessage());
            throw e;
        }
    }

    @Override
    public GrantResult interceptWrite(WriteChain chain) {
        var ctx = chain.operationContext();
        LOG.log(System.Logger.Level.INFO,
                "-> {0} {1}:{2}",
                ctx.action(), ctx.resourceType(), ctx.resourceId());
        long start = System.nanoTime();
        try {
            GrantResult result = chain.proceed(chain.request());
            long ms = (System.nanoTime() - start) / 1_000_000;
            LOG.log(System.Logger.Level.INFO,
                    "<- {0} {1}ms count={2}",
                    ctx.action(), ms, result.count());
            return result;
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            LOG.log(System.Logger.Level.WARNING,
                    "<- {0} {1}ms ERROR: {2}",
                    ctx.action(), ms, e.getMessage());
            throw e;
        }
    }
}
