package com.authx.sdk.transport;

import com.authx.sdk.exception.AuthxAuthException;
import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.WriteRequest;
import com.authx.sdk.model.enums.Permissionship;
import com.authx.sdk.model.enums.SdkAction;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.spi.SdkInterceptor.OperationContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SR:C8 — interceptor chain must isolate user-code exceptions on READ paths
 * (skip the broken interceptor and continue), and fail-closed on WRITE paths
 * (abort and surface a typed exception).
 */
class RealCheckChainIsolationTest {

    private static CheckRequest req() {
        return CheckRequest.of(
                new ResourceRef("document", "doc-1"),
                new Permission("view"),
                new SubjectRef("user", "alice", null),
                Consistency.minimizeLatency());
    }

    private static OperationContext ctx() {
        return new OperationContext(SdkAction.CHECK, "document", "doc-1", "view", "user", "alice");
    }

    private static OperationContext writeCtx() {
        return new OperationContext(SdkAction.WRITE, "document", "doc-1", "", "", "");
    }

    /** Stub transport that returns a fixed allowed result, counting invocations. */
    static final class CountingTransport extends InMemoryTransport {
        final AtomicInteger checkCalls = new AtomicInteger();
        final AtomicInteger writeCalls = new AtomicInteger();
        @Override public CheckResult check(CheckRequest r) {
            checkCalls.incrementAndGet();
            return new CheckResult(Permissionship.HAS_PERMISSION, "tok", Optional.empty());
        }
        @Override public com.authx.sdk.model.GrantResult writeRelationships(
                java.util.List<SdkTransport.RelationshipUpdate> updates) {
            writeCalls.incrementAndGet();
            return new com.authx.sdk.model.GrantResult("tok", updates == null ? 0 : updates.size());
        }
    }

    // ─────────────────────────────────── Read-path isolation

    @Test
    void read_chain_skips_broken_interceptor_and_continues() {
        var aCalls = new AtomicInteger();
        var cCalls = new AtomicInteger();

        SdkInterceptor a = new SdkInterceptor() {
            @Override public CheckResult interceptCheck(CheckChain chain) {
                aCalls.incrementAndGet();
                return chain.proceed(chain.request());
            }
        };
        SdkInterceptor b_broken = new SdkInterceptor() {
            @Override public CheckResult interceptCheck(CheckChain chain) {
                throw new IllegalStateException("simulated bug in user interceptor");
            }
        };
        SdkInterceptor c = new SdkInterceptor() {
            @Override public CheckResult interceptCheck(CheckChain chain) {
                cCalls.incrementAndGet();
                return chain.proceed(chain.request());
            }
        };

        var transport = new CountingTransport();
        var chain = new RealCheckChain(List.of(a, b_broken, c), 0, req(), transport, ctx());
        var result = chain.proceed(req());

        assertEquals(Permissionship.HAS_PERMISSION, result.permissionship(),
                "Despite broken interceptor B, the request must complete.");
        assertEquals(1, aCalls.get(), "A runs");
        assertEquals(1, cCalls.get(), "C runs after B is skipped");
        assertEquals(1, transport.checkCalls.get(), "transport.check() still executes");
    }

    @Test
    void read_chain_propagates_authx_exceptions_unchanged() {
        var authException = new AuthxAuthException("denied", null);
        SdkInterceptor denier = new SdkInterceptor() {
            @Override public CheckResult interceptCheck(CheckChain chain) {
                throw authException;
            }
        };

        var transport = new CountingTransport();
        var chain = new RealCheckChain(List.of(denier), 0, req(), transport, ctx());

        var ex = assertThrows(AuthxAuthException.class, () -> chain.proceed(req()));
        assertSame(authException, ex, "AuthxException subclasses must pass through unchanged");
        assertEquals(0, transport.checkCalls.get(), "transport MUST NOT be called on Authx denial");
    }

    // ─────────────────────────────────── Write-path fail-closed

    @Test
    void write_chain_aborts_on_interceptor_bug() {
        var cCalls = new AtomicInteger();

        SdkInterceptor bug = new SdkInterceptor() {
            @Override public com.authx.sdk.model.GrantResult interceptWrite(WriteChain chain) {
                throw new IllegalStateException("policy enforcement failed");
            }
        };
        SdkInterceptor c = new SdkInterceptor() {
            @Override public com.authx.sdk.model.GrantResult interceptWrite(WriteChain chain) {
                cCalls.incrementAndGet();
                return chain.proceed(chain.request());
            }
        };

        var transport = new CountingTransport();
        var writeReq = new WriteRequest(java.util.List.of());
        var chain = new RealWriteChain(List.of(bug, c), 0, writeReq, transport, writeCtx());

        var ex = assertThrows(com.authx.sdk.exception.AuthxException.class,
                () -> chain.proceed(writeReq));
        assertNotNull(ex.getCause(), "original bug must be preserved as cause");
        assertInstanceOf(IllegalStateException.class, ex.getCause());

        assertEquals(0, cCalls.get(), "downstream interceptor must NOT run after write abort");
        assertEquals(0, transport.writeCalls.get(), "transport.write() must NOT run after abort");
    }
}
