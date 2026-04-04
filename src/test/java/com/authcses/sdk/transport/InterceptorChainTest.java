package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;
import com.authcses.sdk.model.enums.Permissionship;
import com.authcses.sdk.model.enums.SdkAction;
import com.authcses.sdk.spi.AttributeKey;
import com.authcses.sdk.spi.SdkInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the OkHttp-style interceptor chain pattern for check and write operations.
 */
class InterceptorChainTest {

    private InMemoryTransport inner;

    @BeforeEach
    void setup() {
        inner = new InMemoryTransport();
        // Pre-populate: alice is editor on document:d1
        inner.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));
    }

    // ---- 1. Chain proceeds through all interceptors correctly ----

    @Test
    void chainProceedsThroughAllInterceptors() {
        var order = new ArrayList<String>();

        SdkInterceptor first = new SdkInterceptor() {
            @Override
            public CheckResult interceptCheck(SdkInterceptor.CheckChain chain) {
                order.add("first-before");
                CheckResult result = chain.proceed(chain.request());
                order.add("first-after");
                return result;
            }
        };

        SdkInterceptor second = new SdkInterceptor() {
            @Override
            public CheckResult interceptCheck(SdkInterceptor.CheckChain chain) {
                order.add("second-before");
                CheckResult result = chain.proceed(chain.request());
                order.add("second-after");
                return result;
            }
        };

        var transport = new InterceptorTransport(inner, List.of(first, second));
        var result = transport.check(CheckRequest.from(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        assertTrue(result.hasPermission());
        assertEquals(List.of("first-before", "second-before", "second-after", "first-after"), order);
    }

    // ---- 2. Interceptor can short-circuit ----

    @Test
    void interceptorCanShortCircuit() {
        // This interceptor always returns denied without calling proceed
        SdkInterceptor shortCircuit = new SdkInterceptor() {
            @Override
            public CheckResult interceptCheck(SdkInterceptor.CheckChain chain) {
                return CheckResult.denied("short-circuited");
            }
        };

        // This interceptor should never be reached
        SdkInterceptor unreachable = new SdkInterceptor() {
            @Override
            public CheckResult interceptCheck(SdkInterceptor.CheckChain chain) {
                fail("Should not be called — previous interceptor short-circuited");
                return chain.proceed(chain.request());
            }
        };

        var transport = new InterceptorTransport(inner, List.of(shortCircuit, unreachable));
        var result = transport.check(CheckRequest.from(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        assertFalse(result.hasPermission());
        assertEquals("short-circuited", result.zedToken());
    }

    // ---- 3. Interceptor can modify request ----

    @Test
    void interceptorCanModifyRequest() {
        // Also grant bob as viewer
        inner.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d1"),
                Relation.of("viewer"),
                SubjectRef.of("user", "bob", null))));

        // Interceptor rewrites the permission from "editor" to "viewer"
        SdkInterceptor permissionRewriter = new SdkInterceptor() {
            @Override
            public CheckResult interceptCheck(SdkInterceptor.CheckChain chain) {
                CheckRequest original = chain.request();
                CheckRequest modified = CheckRequest.of(
                        original.resource(),
                        Permission.of("viewer"),
                        original.subject(),
                        original.consistency());
                return chain.proceed(modified);
            }
        };

        var transport = new InterceptorTransport(inner, List.of(permissionRewriter));

        // Bob is NOT an editor, but the interceptor rewrites to "viewer" check
        var result = transport.check(CheckRequest.from(
                "document", "d1", "editor", "user", "bob", Consistency.minimizeLatency()));
        assertTrue(result.hasPermission());
    }

    // ---- 4. Interceptor can catch and wrap exceptions ----

    @Test
    void interceptorCanCatchAndWrapExceptions() {
        // Transport that always throws
        SdkTransport failing = new InMemoryTransport() {
            @Override
            public CheckResult check(CheckRequest request) {
                throw new RuntimeException("SpiceDB unreachable");
            }
        };

        // Interceptor catches and returns a fallback
        SdkInterceptor errorHandler = new SdkInterceptor() {
            @Override
            public CheckResult interceptCheck(SdkInterceptor.CheckChain chain) {
                try {
                    return chain.proceed(chain.request());
                } catch (RuntimeException e) {
                    // Fallback: deny on error
                    return CheckResult.denied("fallback-on-error");
                }
            }
        };

        var transport = new InterceptorTransport(failing, List.of(errorHandler));
        var result = transport.check(CheckRequest.from(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        assertFalse(result.hasPermission());
        assertEquals("fallback-on-error", result.zedToken());
    }

    // ---- 5. Empty interceptor list goes directly to transport ----

    @Test
    void emptyInterceptorListGoesDirectlyToTransport() {
        var transport = new InterceptorTransport(inner, List.of());
        var result = transport.check(CheckRequest.from(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        assertTrue(result.hasPermission());
    }

    // ---- 6. Legacy before/after interceptors still work through the default bridge ----

    @Test
    void legacyBeforeAfterStillWorkViaDefaultBridge() {
        var events = new ArrayList<String>();

        SdkInterceptor legacyInterceptor = new SdkInterceptor() {
            @Override
            public void before(SdkInterceptor.OperationContext ctx) {
                events.add("before:" + ctx.action());
            }

            @Override
            public void after(SdkInterceptor.OperationContext ctx) {
                events.add("after:" + ctx.result());
            }
        };

        var transport = new InterceptorTransport(inner, List.of(legacyInterceptor));
        var result = transport.check(CheckRequest.from(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        assertTrue(result.hasPermission());
        assertEquals(List.of("before:" + SdkAction.CHECK, "after:" + Permissionship.HAS_PERMISSION.name()), events);
    }

    // ---- 7. Write chain proceeds through interceptors ----

    @Test
    void writeChainProceedsThroughInterceptors() {
        var events = new ArrayList<String>();

        SdkInterceptor writeInterceptor = new SdkInterceptor() {
            @Override
            public GrantResult interceptWrite(SdkInterceptor.WriteChain chain) {
                events.add("write-before");
                GrantResult result = chain.proceed(chain.request());
                events.add("write-after:" + result.count());
                return result;
            }
        };

        var transport = new InterceptorTransport(inner, List.of(writeInterceptor));
        var result = transport.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d2"),
                Relation.of("viewer"),
                SubjectRef.of("user", "bob", null))));

        assertEquals(1, result.count());
        assertEquals(List.of("write-before", "write-after:1"), events);
    }

    // ---- 8. Write chain with legacy interceptor ----

    @Test
    void writeChainWithLegacyBeforeAfter() {
        var events = new ArrayList<String>();

        SdkInterceptor legacyInterceptor = new SdkInterceptor() {
            @Override
            public void before(SdkInterceptor.OperationContext ctx) {
                events.add("before:" + ctx.action());
            }

            @Override
            public void after(SdkInterceptor.OperationContext ctx) {
                events.add("after");
            }
        };

        var transport = new InterceptorTransport(inner, List.of(legacyInterceptor));
        transport.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d3"),
                Relation.of("viewer"),
                SubjectRef.of("user", "carol", null))));

        assertEquals(List.of("before:" + SdkAction.WRITE, "after"), events);
    }

    // ---- 9. Chain attributes are shared between interceptors ----

    @Test
    void chainAttributesSharedBetweenInterceptors() {
        var TRACE_ID = AttributeKey.of("traceId", String.class);

        SdkInterceptor setter = new SdkInterceptor() {
            @Override
            public CheckResult interceptCheck(SdkInterceptor.CheckChain chain) {
                chain.attr(TRACE_ID, "trace-abc-123");
                return chain.proceed(chain.request());
            }
        };

        var capturedTraceId = new String[1];
        SdkInterceptor reader = new SdkInterceptor() {
            @Override
            public CheckResult interceptCheck(SdkInterceptor.CheckChain chain) {
                capturedTraceId[0] = chain.attr(TRACE_ID);
                return chain.proceed(chain.request());
            }
        };

        var transport = new InterceptorTransport(inner, List.of(setter, reader));
        transport.check(CheckRequest.from(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        assertEquals("trace-abc-123", capturedTraceId[0]);
    }

    // ---- 10. Interceptor can time the call ----

    @Test
    void interceptorCanTimeProceedCall() {
        var capturedDuration = new long[1];

        SdkInterceptor timer = new SdkInterceptor() {
            @Override
            public CheckResult interceptCheck(SdkInterceptor.CheckChain chain) {
                long start = System.nanoTime();
                CheckResult result = chain.proceed(chain.request());
                capturedDuration[0] = System.nanoTime() - start;
                return result;
            }
        };

        var transport = new InterceptorTransport(inner, List.of(timer));
        transport.check(CheckRequest.from(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        assertTrue(capturedDuration[0] > 0, "Duration should be positive");
    }

    // ---- 11. Exception propagation without error handler ----

    @Test
    void exceptionPropagatesWhenNotCaught() {
        SdkTransport failing = new InMemoryTransport() {
            @Override
            public CheckResult check(CheckRequest request) {
                throw new RuntimeException("connection refused");
            }
        };

        // Interceptor that does NOT catch — exception should propagate
        SdkInterceptor passthrough = new SdkInterceptor() {
            @Override
            public CheckResult interceptCheck(SdkInterceptor.CheckChain chain) {
                return chain.proceed(chain.request());
            }
        };

        var transport = new InterceptorTransport(failing, List.of(passthrough));

        var ex = assertThrows(RuntimeException.class, () ->
                transport.check(CheckRequest.from(
                        "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency())));
        assertEquals("connection refused", ex.getMessage());
    }

    // ---- 12. Mixed old-style and new-style interceptors ----

    @Test
    void mixedOldAndNewStyleInterceptors() {
        var events = new ArrayList<String>();

        // Old-style interceptor using before/after
        SdkInterceptor legacy = new SdkInterceptor() {
            @Override
            public void before(SdkInterceptor.OperationContext ctx) {
                events.add("legacy-before");
            }
            @Override
            public void after(SdkInterceptor.OperationContext ctx) {
                events.add("legacy-after");
            }
        };

        // New-style interceptor using interceptCheck
        SdkInterceptor modern = new SdkInterceptor() {
            @Override
            public CheckResult interceptCheck(SdkInterceptor.CheckChain chain) {
                events.add("modern-before");
                CheckResult result = chain.proceed(chain.request());
                events.add("modern-after");
                return result;
            }
        };

        var transport = new InterceptorTransport(inner, List.of(legacy, modern));
        transport.check(CheckRequest.from(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        // legacy wraps around modern due to chain ordering
        assertEquals(List.of("legacy-before", "modern-before", "modern-after", "legacy-after"), events);
    }
}
