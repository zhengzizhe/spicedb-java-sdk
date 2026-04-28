package com.authx.testapp.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class HttpRequestTracingFilter extends OncePerRequestFilter {

    private static final AttributeKey<String> HTTP_METHOD =
            AttributeKey.stringKey("http.request.method");
    private static final AttributeKey<String> URL_PATH =
            AttributeKey.stringKey("url.path");
    private static final AttributeKey<String> HTTP_ROUTE =
            AttributeKey.stringKey("http.route");
    private static final AttributeKey<Long> HTTP_STATUS_CODE =
            AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<String> ERROR_TYPE =
            AttributeKey.stringKey("error.type");
    private static final AttributeKey<String> USER_AGENT =
            AttributeKey.stringKey("user_agent.original");

    private static final TextMapGetter<HttpServletRequest> REQUEST_GETTER =
            new TextMapGetter<HttpServletRequest>() {
                @Override
                public Iterable<String> keys(HttpServletRequest carrier) {
                    if (carrier == null || carrier.getHeaderNames() == null) {
                        return Collections.emptyList();
                    }
                    return Collections.list(carrier.getHeaderNames());
                }

                @Override
                public String get(HttpServletRequest carrier, String key) {
                    if (carrier == null || key == null) {
                        return null;
                    }
                    return carrier.getHeader(key);
                }
            };

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;
    private final boolean tracingEnabled;

    public HttpRequestTracingFilter(
            OpenTelemetry openTelemetry,
            @Value("${authx.tracing.enabled:true}") boolean tracingEnabled) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer("authx-testapp-http", "1.0.0");
        this.tracingEnabled = tracingEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if (!tracingEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        Context parentContext = openTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), request, REQUEST_GETTER);
        SpanBuilder spanBuilder = tracer.spanBuilder(initialSpanName(request))
                .setParent(parentContext)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(HTTP_METHOD, request.getMethod())
                .setAttribute(URL_PATH, request.getRequestURI());
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && !userAgent.isBlank()) {
            spanBuilder.setAttribute(USER_AGENT, userAgent);
        }

        Span span = spanBuilder.startSpan();
        try (Scope ignored = span.makeCurrent(); MdcScope mdcScope = MdcScope.push(span)) {
            filterChain.doFilter(request, response);
            setRouteAttributes(span, request);
            span.setAttribute(HTTP_STATUS_CODE, (long) response.getStatus());
            if (response.getStatus() >= 500) {
                span.setStatus(StatusCode.ERROR);
            } else {
                span.setStatus(StatusCode.OK);
            }
        } catch (IOException | ServletException | RuntimeException | Error e) {
            span.setAttribute(ERROR_TYPE, e.getClass().getName());
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }

    private static String initialSpanName(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }

    private static void setRouteAttributes(Span span, HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String route && !route.isBlank()) {
            span.updateName(request.getMethod() + " " + route);
            span.setAttribute(HTTP_ROUTE, route);
        }
    }

    private record MdcScope(String previousTraceId, String previousSpanId,
                            boolean pushedTraceId, boolean pushedSpanId) implements AutoCloseable {

        static MdcScope push(Span span) {
            SpanContext context = span.getSpanContext();
            if (!context.isValid()) {
                return new MdcScope(null, null, false, false);
            }
            String previousTraceId = MDC.get("trace_id");
            String previousSpanId = MDC.get("span_id");
            MDC.put("trace_id", context.getTraceId());
            MDC.put("span_id", context.getSpanId());
            return new MdcScope(previousTraceId, previousSpanId, true, true);
        }

        @Override
        public void close() {
            if (pushedTraceId) {
                restore("trace_id", previousTraceId);
            }
            if (pushedSpanId) {
                restore("span_id", previousSpanId);
            }
        }

        private static void restore(String key, String previousValue) {
            if (previousValue == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previousValue);
            }
        }
    }
}
