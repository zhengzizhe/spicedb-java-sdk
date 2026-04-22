package com.authx.testapp;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.trace.LogCtx;
import com.authx.sdk.trace.LogFields;
import com.authx.sdk.trace.Slf4jMdcBridge;
import com.authx.testapp.schema.Document;
import static com.authx.testapp.schema.Schema.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dedicated controller for exercising the 2026-04-20 logging & traceability
 * upgrade. Each endpoint triggers a different log scenario so you can see
 * the format live in the test-app console.
 *
 * <p>All endpoints reuse the SDK's own public classes
 * ({@link LogCtx}, {@link LogFields}, {@link Slf4jMdcBridge}) — the same
 * ones SDK internals use — so what you see here is byte-for-byte the
 * format SDK log sites produce. The controller also activates an OTel
 * {@link Span} scope so {@code LogCtx.fmt(...)} can read a live
 * {@code Span.current()}.
 */
@RestController
@RequestMapping("/logdemo")
public class LogDemoController {

    private static final Logger LOG = LoggerFactory.getLogger("com.authx.sdk.demo");

    private final AuthxClient client;
    private final Executor asyncExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "logdemo-listener");
        t.setDaemon(true);
        return t;
    });

    public LogDemoController(AuthxClient client) {
        this.client = client;
    }

    // ---- /logdemo/basic — INFO + DEBUG flow on a successful check ----

    @GetMapping("/basic")
    public Map<String, Object> basic(@RequestParam(defaultValue = "alice") String user,
                                      @RequestParam(defaultValue = "doc-42") String doc) {
        try (Scope span = activateSpan();
             Closeable mdc = pushMdc("CHECK", "document", doc, "view", null, "user:" + user, "minimizeLatency")) {

            LOG.info(LogCtx.fmt("Incoming check for {0}:{1} by {2}", "document", doc, "user:" + user));

            boolean allowed;
            try {
                allowed = client.on(Document)
                        .select(doc)
                        .check(Document.Perm.VIEW)
                        .by(user);
            } catch (Exception e) {
                // In-memory transport or unseeded data — we just want the log line
                allowed = false;
                LOG.debug(LogCtx.fmt("Check returned false on empty store (expected in demo): {0}", e.getClass().getSimpleName()));
            }

            LOG.debug(LogCtx.fmt("Check done, result={0}", allowed));
            return Map.of("allowed", allowed, "user", user, "doc", doc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- /logdemo/warn-interceptor-throws — demonstrates WARN + suffix ----

    @GetMapping("/warn-interceptor-throws")
    public Map<String, Object> warnInterceptorThrows(@RequestParam(defaultValue = "alice") String user,
                                                      @RequestParam(defaultValue = "doc-42") String doc) {
        try (Scope span = activateSpan();
             Closeable mdc = pushMdc("CHECK", "document", doc, "view", null, "user:" + user, "minimizeLatency")) {

            // Same message body & format as the real RealCheckChain WARN site,
            // built via the same LogCtx.fmt + LogFields.suffixPerm pipeline.
            LOG.warn(LogCtx.fmt(
                    "Read interceptor {0} threw {1}; skipping and continuing the chain."
                            + LogFields.suffixPerm("document", doc, "view", "user:" + user),
                    "com.example.AuditInterceptor",
                    "java.lang.NullPointerException: audit log flush failed"));

            return Map.of("status", "logged a WARN with suffix — check the console");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- /logdemo/retry-debug — demonstrates the WARN→DEBUG downgrade ----

    @GetMapping("/retry-debug")
    public Map<String, Object> retryDebug() {
        try (Scope span = activateSpan();
             Closeable mdc = pushMdc("CHECK", "document", "doc-42", "view", null, "user:alice", "minimizeLatency")) {

            // Previously WARN; now DEBUG per SR:req-10 (retry is a normal product
            // of the resilience policy, not operator-actionable).
            LOG.debug(LogCtx.fmt(
                    "Retry {0}/{1} for [{2}]: {3}",
                    1, 3, "document", "timeout waiting for SpiceDB response after 250ms"));
            LOG.debug(LogCtx.fmt(
                    "Retry {0}/{1} for [{2}]: {3}",
                    2, 3, "document", "connection reset"));

            // Only the exhaustion line stays WARN (real signal worth paging on)
            LOG.warn(LogCtx.fmt(
                    "Retry budget exhausted for [{0}], skipping retry"
                            + LogFields.suffix("document", null, null, null),
                    "document"));

            return Map.of("status", "logged 2 retry DEBUGs + 1 exhaustion WARN");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- /logdemo/error — demonstrates ERROR + suffix on lifecycle failure ----

    @GetMapping("/error")
    public Map<String, Object> errorLifecycle() {
        try (Scope span = activateSpan();
             Closeable mdc = pushMdc("STARTUP", "document", null, null, null, null, null)) {

            LOG.error(LogCtx.fmt(
                    "Startup phase {0} failed after {1}ms: {2}"
                            + LogFields.suffix("document", null, null, null),
                    "CONNECT", 150, "io.grpc.StatusRuntimeException: UNAVAILABLE: connection refused"));

            return Map.of("status", "logged an ERROR with suffix");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- /logdemo/listener — Write Listener API (sync + async + async-throws) ----

    /**
     * Demonstrates the three flavors of {@link com.authx.sdk.action.GrantCompletion}
     * listener:
     *
     * <ol>
     *   <li><b>Sync</b> {@code .listener(cb)} — cb runs on the caller thread
     *       immediately after grant, before {@code listener(...)} returns.</li>
     *   <li><b>Async success</b> {@code .listenerAsync(cb, exec)} — cb runs
     *       on a separate executor (here the {@code logdemo-listener} thread).</li>
     *   <li><b>Async failure</b> {@code .listenerAsync(badCb, exec)} — cb
     *       throws, SDK catches it in {@code GrantCompletionImpl} and logs
     *       a single WARN ("Async grant listener threw ..."). The caller is
     *       NOT notified of the failure — this is the SG-1 zero-throw
     *       guarantee for listener bugs.</li>
     * </ol>
     */
    @GetMapping("/listener")
    public Map<String, Object> listenerDemo(@RequestParam(defaultValue = "gary") String user,
                                              @RequestParam(defaultValue = "doc-42") String doc) {
        var completion = client.on(Document)
                .select(doc)
                .grant(Document.Rel.VIEWER)
                .to(SubjectRef.of("user", user));

        LOG.info(LogCtx.fmt("grant returned — zedToken={0}, count={1}",
                completion.result().zedToken(), completion.result().count()));

        // (1) sync listener — runs inline
        completion.listener(r ->
                LOG.info(LogCtx.fmt("SYNC  listener fired on thread={0}, token={1}",
                        Thread.currentThread().getName(), r.zedToken())));

        // (2) async listener (succeeds) — runs on logdemo-listener thread
        completion.listenerAsync(r ->
                LOG.info(LogCtx.fmt("ASYNC listener fired on thread={0}, token={1}",
                        Thread.currentThread().getName(), r.zedToken())),
                asyncExec);

        // (3) async listener (throws) — SDK swallows + logs WARN, caller unaffected
        completion.listenerAsync(r -> {
            throw new RuntimeException("simulated listener bug");
        }, asyncExec);

        return Map.of(
                "user", user,
                "doc", doc,
                "zedToken", String.valueOf(completion.result().zedToken()),
                "count", completion.result().count(),
                "status", "grant committed; 3 listeners fired (1 sync, 2 async — 1 throws)");
    }

    // ---- /logdemo/all — one hit triggers every level for a side-by-side view ----

    @GetMapping("/all")
    public Map<String, Object> all(@RequestParam(defaultValue = "alice") String user,
                                    @RequestParam(defaultValue = "doc-42") String doc) {
        basic(user, doc);
        retryDebug();
        warnInterceptorThrows(user, doc);
        errorLifecycle();
        return Map.of("status", "emitted one line per level — scroll up");
    }

    // ---- helpers ----

    /**
     * Activate a fresh OTel span for this request so {@code Span.current()}
     * (which LogCtx reads) returns something valid even when the host app
     * hasn't installed full OTel auto-instrumentation. Generates random
     * trace+span ids each call so multi-request output is easy to correlate.
     */
    private static Scope activateSpan() {
        var rnd = ThreadLocalRandom.current();
        String traceId = String.format("%016x%016x", rnd.nextLong(), rnd.nextLong());
        String spanId = String.format("%016x", rnd.nextLong());
        SpanContext sc = SpanContext.create(
                traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
        return Context.current().with(Span.wrap(sc)).makeCurrent();
    }

    private static Closeable pushMdc(String action, String resType, String resId,
                                      String permission, String relation,
                                      String subject, String consistency) {
        return Slf4jMdcBridge.push(LogFields.toMdcMap(
                action, resType, resId, permission, relation, subject, consistency));
    }
}
