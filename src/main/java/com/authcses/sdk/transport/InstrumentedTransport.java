package com.authcses.sdk.transport;

import com.authcses.sdk.metrics.SdkMetrics;
import com.authcses.sdk.model.*;
import com.authcses.sdk.model.enums.OperationResult;
import com.authcses.sdk.model.enums.SdkAction;
import com.authcses.sdk.telemetry.TelemetryReporter;
import com.authcses.sdk.trace.TraceContext;

import java.util.List;
import java.util.function.Supplier;

/**
 * Wraps a SdkTransport and records telemetry + metrics + trace for each operation.
 */
public class InstrumentedTransport implements SdkTransport {

    private final SdkTransport delegate;
    private final TelemetryReporter reporter;
    private final SdkMetrics metrics;

    public InstrumentedTransport(SdkTransport delegate, TelemetryReporter reporter, SdkMetrics metrics) {
        this.delegate = delegate;
        this.reporter = reporter;
        this.metrics = metrics;
    }

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency) {
        return instrument(SdkAction.CHECK, resourceType, resourceId, subjectType, subjectId, permission,
                () -> {
                    var r = delegate.check(resourceType, resourceId, permission, subjectType, subjectId, consistency);
                    return new InstrumentedResult<>(r, r.permissionship().name());
                });
    }

    @Override
    public BulkCheckResult checkBulk(String resourceType, String resourceId,
                                     String permission, List<String> subjectIds, String defaultSubjectType,
                                     Consistency consistency) {
        return instrument(SdkAction.CHECK_BULK, resourceType, resourceId, defaultSubjectType, "", permission,
                () -> new InstrumentedResult<>(
                        delegate.checkBulk(resourceType, resourceId, permission, subjectIds, defaultSubjectType, consistency),
                        "SUCCESS"));
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        return instrument(SdkAction.WRITE, "", "", "", "", "",
                () -> new InstrumentedResult<>(delegate.writeRelationships(updates), OperationResult.SUCCESS.name()));
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        return instrument(SdkAction.DELETE, "", "", "", "", "",
                () -> new InstrumentedResult<>(delegate.deleteRelationships(updates), OperationResult.SUCCESS.name()));
    }

    @Override
    public List<Tuple> readRelationships(String resourceType, String resourceId,
                                          String relation, Consistency consistency) {
        return instrument(SdkAction.READ, resourceType, resourceId, "", "", relation,
                () -> new InstrumentedResult<>(
                        delegate.readRelationships(resourceType, resourceId, relation, consistency),
                        "SUCCESS"));
    }

    @Override
    public List<String> lookupSubjects(String resourceType, String resourceId,
                                        String permission, String subjectType,
                                        Consistency consistency) {
        return instrument(SdkAction.LOOKUP_SUBJECTS, resourceType, resourceId, subjectType, "", permission,
                () -> new InstrumentedResult<>(
                        delegate.lookupSubjects(resourceType, resourceId, permission, subjectType, consistency),
                        "SUCCESS"));
    }

    @Override
    public List<String> lookupResources(String resourceType, String permission,
                                         String subjectType, String subjectId,
                                         Consistency consistency) {
        return instrument(SdkAction.LOOKUP_RESOURCES, resourceType, "", subjectType, subjectId, permission,
                () -> new InstrumentedResult<>(
                        delegate.lookupResources(resourceType, permission, subjectType, subjectId, consistency),
                        "SUCCESS"));
    }

    @Override
    public void close() {
        delegate.close();
    }

    private record InstrumentedResult<T>(T value, String resultLabel) {}

    private <T> T instrument(SdkAction action, String resourceType, String resourceId,
                              String subjectType, String subjectId, String permission,
                              Supplier<InstrumentedResult<T>> call) {
        // OTel span: child of current active span (business request)
        var spanAttrs = java.util.Map.of(
                "authcses.action", action.name(),
                "authcses.resource.type", resourceType != null ? resourceType : "",
                "authcses.resource.id", resourceId != null ? resourceId : "",
                "authcses.permission", permission != null ? permission : "");

        long start = System.nanoTime();
        String result = OperationResult.ERROR.name();
        boolean isError = true;
        String traceId = TraceContext.traceId();

        try (var span = TraceContext.startSpan("authcses." + action.name().toLowerCase(), spanAttrs)) {
            try {
                var ir = call.get();
                result = ir.resultLabel;
                isError = false;
                span.setSuccess();
                span.setAttribute("authcses.result", result);
                return ir.value;
            } catch (Exception e) {
                span.setError(e);
                throw e;
            }
        } finally {
            long nanos = System.nanoTime() - start;
            long ms = nanos / 1_000_000;
            long micros = nanos / 1_000;

            reporter.record(action.name(), resourceType, resourceId,
                    subjectType, subjectId, permission, result, ms, traceId);

            if (metrics != null) {
                metrics.recordRequest(micros, isError);
            }
        }
    }
}
