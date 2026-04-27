package com.authx.sdk.transport;

import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.SdkAction;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.spi.SdkInterceptor.OperationContext;
import com.authx.sdk.trace.LogFields;
import com.authx.sdk.trace.Slf4jMdcBridge;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Runs registered interceptors for each operation on the delegate transport.
 *
 * <p>All operations use OkHttp-style chains:
 * <ul>
 *   <li>{@code check()} — {@link RealCheckChain} (interceptors can modify CheckRequest)
 *   <li>{@code writeRelationships()} — {@link RealWriteChain} (interceptors can modify WriteRequest)
 *   <li>All other operations — {@link RealOperationChain} (generic chain for cross-cutting concerns)
 * </ul>
 *
 * <p>Also serves as the SDK's SLF4J MDC boundary: each RPC entry pushes
 * {@code authx.*} fields via {@link Slf4jMdcBridge} for the duration of
 * the call. If SLF4J is absent from the classpath the push is noop — no
 * behavior change, no extra allocation (see Slf4jMdcBridge Javadoc).
 */
public class InterceptorTransport extends ForwardingTransport {

    private final SdkTransport delegate;
    private final List<SdkInterceptor> interceptors;

    public InterceptorTransport(SdkTransport delegate, List<SdkInterceptor> interceptors) {
        this.delegate = delegate;
        this.interceptors = List.copyOf(interceptors);
    }

    @Override
    protected SdkTransport delegate() {
        return delegate;
    }

    // ---- Chain-based operations ----

    @Override
    public CheckResult check(CheckRequest request) {
        Map<String, String> mdc = mdcFields("CHECK",
                request.resource().type(), request.resource().id(),
                request.permission().name(), null,
                refOf(request.subject()),
                consistencyLabel(request.consistency()));
        try (Closeable ignored = Slf4jMdcBridge.push(mdc)) {
            if (interceptors.isEmpty()) return delegate.check(request);
            SdkInterceptor.OperationContext ctx = buildCheckContext(request);
            RealCheckChain chain = new RealCheckChain(interceptors, 0, request, delegate, ctx);
            return chain.proceed(request);
        } catch (IOException e) {
            throw new RuntimeException("Unreachable: MDC Closeable does not throw", e);
        }
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        String resType = updates.isEmpty() ? "" : updates.getFirst().resource().type();
        String resId = updates.isEmpty() ? "" : updates.getFirst().resource().id();
        String rel = updates.isEmpty() ? null : updates.getFirst().relation().name();
        String subj = updates.isEmpty() ? null : refOf(updates.getFirst().subject());
        Map<String, String> mdc = mdcFields("GRANT", resType, resId, null, rel, subj, null);
        try (Closeable ignored = Slf4jMdcBridge.push(mdc)) {
            if (interceptors.isEmpty()) return delegate.writeRelationships(updates);
            SdkInterceptor.OperationContext ctx = new OperationContext(SdkAction.WRITE, resType, resId, "", "", "");
            WriteRequest writeRequest = new WriteRequest(updates);
            RealWriteChain chain = new RealWriteChain(interceptors, 0, writeRequest, delegate, ctx);
            return chain.proceed(writeRequest);
        } catch (IOException e) {
            throw new RuntimeException("Unreachable: MDC Closeable does not throw", e);
        }
    }

    // ---- Generic chain-based operations ----

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        String resType = updates.isEmpty() ? "" : updates.getFirst().resource().type();
        String resId = updates.isEmpty() ? "" : updates.getFirst().resource().id();
        String rel = updates.isEmpty() ? null : updates.getFirst().relation().name();
        String subj = updates.isEmpty() ? null : refOf(updates.getFirst().subject());
        Map<String, String> mdc = mdcFields("REVOKE", resType, resId, null, rel, subj, null);
        try (Closeable ignored = Slf4jMdcBridge.push(mdc)) {
            if (interceptors.isEmpty()) return delegate.deleteRelationships(updates);
            SdkInterceptor.OperationContext ctx = new OperationContext(SdkAction.DELETE, resType, resId, "", "", "");
            return chainOperation(ctx, () -> delegate.deleteRelationships(updates));
        } catch (IOException e) {
            throw new RuntimeException("Unreachable: MDC Closeable does not throw", e);
        }
    }

    @Override
    public RevokeResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                        Relation optionalRelation) {
        Map<String, String> mdc = mdcFields("REVOKE", resource.type(), resource.id(),
                null,
                optionalRelation == null ? null : optionalRelation.name(),
                refOf(subject), null);
        try (Closeable ignored = Slf4jMdcBridge.push(mdc)) {
            if (interceptors.isEmpty()) return delegate.deleteByFilter(resource, subject, optionalRelation);
            SdkInterceptor.OperationContext ctx = new OperationContext(SdkAction.DELETE, resource.type(), resource.id(),
                    "", subject.type(), subject.id());
            return chainOperation(ctx, () -> delegate.deleteByFilter(resource, subject, optionalRelation));
        } catch (IOException e) {
            throw new RuntimeException("Unreachable: MDC Closeable does not throw", e);
        }
    }

    @Override
    public List<Tuple> readRelationships(ResourceRef resource, Relation relation,
                                          Consistency consistency) {
        Map<String, String> mdc = mdcFields("READ", resource.type(), resource.id(),
                null,
                relation == null ? null : relation.name(),
                null, consistencyLabel(consistency));
        try (Closeable ignored = Slf4jMdcBridge.push(mdc)) {
            if (interceptors.isEmpty()) return delegate.readRelationships(resource, relation, consistency);
            SdkInterceptor.OperationContext ctx = new OperationContext(SdkAction.READ, resource.type(), resource.id(),
                    relation != null ? relation.name() : "", "", "");
            return chainOperation(ctx, () -> delegate.readRelationships(resource, relation, consistency));
        } catch (IOException e) {
            throw new RuntimeException("Unreachable: MDC Closeable does not throw", e);
        }
    }

    @Override
    public List<SubjectRef> lookupSubjects(LookupSubjectsRequest request) {
        Map<String, String> mdc = mdcFields("LOOKUP",
                request.resource().type(), request.resource().id(),
                request.permission().name(), null, null,
                consistencyLabel(request.consistency()));
        try (Closeable ignored = Slf4jMdcBridge.push(mdc)) {
            if (interceptors.isEmpty()) return delegate.lookupSubjects(request);
            SdkInterceptor.OperationContext ctx = new OperationContext(SdkAction.LOOKUP_SUBJECTS,
                    request.resource().type(), request.resource().id(),
                    request.permission().name(), request.subjectType(), "");
            return chainOperation(ctx, () -> delegate.lookupSubjects(request));
        } catch (IOException e) {
            throw new RuntimeException("Unreachable: MDC Closeable does not throw", e);
        }
    }

    @Override
    public List<ResourceRef> lookupResources(LookupResourcesRequest request) {
        Map<String, String> mdc = mdcFields("LOOKUP",
                request.resourceType(), null,
                request.permission().name(), null,
                refOf(request.subject()),
                consistencyLabel(request.consistency()));
        try (Closeable ignored = Slf4jMdcBridge.push(mdc)) {
            if (interceptors.isEmpty()) return delegate.lookupResources(request);
            SdkInterceptor.OperationContext ctx = new OperationContext(SdkAction.LOOKUP_RESOURCES,
                    request.resourceType(), "",
                    request.permission().name(),
                    request.subject().type(), request.subject().id());
            return chainOperation(ctx, () -> delegate.lookupResources(request));
        } catch (IOException e) {
            throw new RuntimeException("Unreachable: MDC Closeable does not throw", e);
        }
    }

    @Override
    public ExpandTree expand(ResourceRef resource, Permission permission,
                              Consistency consistency) {
        Map<String, String> mdc = mdcFields("EXPAND", resource.type(), resource.id(),
                permission.name(), null, null, consistencyLabel(consistency));
        try (Closeable ignored = Slf4jMdcBridge.push(mdc)) {
            if (interceptors.isEmpty()) return delegate.expand(resource, permission, consistency);
            SdkInterceptor.OperationContext ctx = new OperationContext(SdkAction.EXPAND, resource.type(), resource.id(),
                    permission.name(), "", "");
            return chainOperation(ctx, () -> delegate.expand(resource, permission, consistency));
        } catch (IOException e) {
            throw new RuntimeException("Unreachable: MDC Closeable does not throw", e);
        }
    }

    @Override
    public BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects) {
        Map<String, String> mdc = mdcFields("CHECK",
                request.resource().type(), request.resource().id(),
                request.permission().name(), null, null,
                consistencyLabel(request.consistency()));
        try (Closeable ignored = Slf4jMdcBridge.push(mdc)) {
            if (interceptors.isEmpty()) return delegate.checkBulk(request, subjects);
            SdkInterceptor.OperationContext ctx = new OperationContext(SdkAction.CHECK_BULK,
                    request.resource().type(), request.resource().id(),
                    request.permission().name(),
                    request.subject().type(), request.subject().id());
            return chainOperation(ctx, () -> delegate.checkBulk(request, subjects));
        } catch (IOException e) {
            throw new RuntimeException("Unreachable: MDC Closeable does not throw", e);
        }
    }

    @Override
    public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
        String resType = items.isEmpty() ? "" : items.getFirst().resource().type();
        Map<String, String> mdc = mdcFields("CHECK", resType, null, null, null, null,
                consistencyLabel(consistency));
        try (Closeable ignored = Slf4jMdcBridge.push(mdc)) {
            if (interceptors.isEmpty()) return delegate.checkBulkMulti(items, consistency);
            SdkInterceptor.OperationContext ctx = new OperationContext(SdkAction.CHECK_BULK, resType, "", "", "", "");
            return chainOperation(ctx, () -> delegate.checkBulkMulti(items, consistency));
        } catch (IOException e) {
            throw new RuntimeException("Unreachable: MDC Closeable does not throw", e);
        }
    }

    @Override
    public void close() {
        delegate.close();
    }

    // ---- Internal helpers ----

    private <T> T chainOperation(OperationContext ctx, Supplier<T> terminalOperation) {
        RealOperationChain<T> chain = new RealOperationChain<>(interceptors, 0, terminalOperation, ctx);
        return chain.proceed();
    }

    private OperationContext buildCheckContext(CheckRequest request) {
        return new OperationContext(SdkAction.CHECK,
                request.resource().type(), request.resource().id(),
                request.permission().name(),
                request.subject().type(), request.subject().id());
    }

    /**
     * Build MDC fields for an RPC, covering traceId/spanId/action/
     * resource/perm-or-rel/subject/consistency. The returned map is
     * ready for {@link Slf4jMdcBridge#push(Map)}; when SLF4J is absent
     * the push itself is noop.
     */
    private static Map<String, String> mdcFields(String action, String resourceType,
                                                   String resourceId, String permission,
                                                   String relation, String subjectRef,
                                                   String consistency) {
        Map<String, String> m = new LinkedHashMap<>(
                LogFields.toMdcMap(action, resourceType, resourceId,
                        permission, relation, subjectRef, consistency));
        try {
            SpanContext ctx = Span.current().getSpanContext();
            if (ctx.isValid()) {
                m.put(LogFields.KEY_TRACE_ID, ctx.getTraceId());
                m.put(LogFields.KEY_SPAN_ID, ctx.getSpanId());
            }
        } catch (Throwable ignored) {
            /* trace info is best-effort */
        }
        return m;
    }

    private static String refOf(SubjectRef s) {
        return s == null ? null : s.toRefString();
    }

    private static String consistencyLabel(Consistency c) {
        return c == null ? null : c.getClass().getSimpleName().toLowerCase();
    }
}
