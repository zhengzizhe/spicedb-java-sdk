package com.authx.sdk.trace;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional SLF4J MDC bridge. When SLF4J is on classpath, pushes
 * {@code authx.*} fields into MDC so Logback/Log4j patterns and JSON
 * encoders can render them natively. When SLF4J is absent, all methods
 * are noop (zero allocation, zero class-loader action on SLF4J classes).
 *
 * <p><b>Lifecycle:</b> The returned {@link Closeable} MUST be closed (use
 * try-with-resources) to pop the keys. Double-close is idempotent.
 *
 * <p><b>Stability (SG-1, SG-2, SG-3):</b>
 * <ul>
 *   <li>SLF4J absent → {@link #SLF4J_PRESENT} is {@code false}; push returns
 *       shared {@link #NOOP} Closeable; no SLF4J classes loaded.</li>
 *   <li>SLF4J present but first MDC.put throws (rare, e.g. classpath
 *       conflict) → bridge is permanently disabled via {@link #disabled},
 *       logs one WARNING via {@link System.Logger}, all subsequent pushes
 *       return NOOP.</li>
 *   <li>All bridge methods catch {@link Throwable} and degrade gracefully.</li>
 * </ul>
 */
public final class Slf4jMdcBridge {

    private static final System.Logger LOG = System.getLogger(Slf4jMdcBridge.class.getName());

    /** Shared zero-allocation noop Closeable — returned when bridge is inactive. */
    private static final Closeable NOOP = () -> {};

    /** True when {@code org.slf4j.MDC} is on the classpath. Probed once at class init. */
    public static final boolean SLF4J_PRESENT = probe();

    /** Set true the first time a MDC operation throws; all subsequent calls noop. */
    private static final AtomicBoolean disabled = new AtomicBoolean(false);

    private Slf4jMdcBridge() {}

    private static boolean probe() {
        try {
            Class.forName("org.slf4j.MDC");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Push the given fields into SLF4J MDC; returns a {@link Closeable}
     * that pops them on close. Null or empty field maps, SLF4J absent, or
     * a previously-disabled bridge all return the shared {@link #NOOP}.
     *
     * @param fields map of MDC key → value; null/empty returns NOOP
     * @return a Closeable that removes the pushed keys on close
     */
    public static Closeable push(Map<String, String> fields) {
        if (!SLF4J_PRESENT || disabled.get()) return NOOP;
        if (fields == null || fields.isEmpty()) return NOOP;
        try {
            List<String> pushedKeys = new ArrayList<>(fields.size());
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                org.slf4j.MDC.put(e.getKey(), e.getValue());
                pushedKeys.add(e.getKey());
            }
            if (pushedKeys.isEmpty()) return NOOP;
            return () -> {
                for (String k : pushedKeys) {
                    try { org.slf4j.MDC.remove(k); }
                    catch (Throwable ignore) { /* continue popping others */ }
                }
            };
        } catch (Throwable t) {
            if (disabled.compareAndSet(false, true)) {
                LOG.log(System.Logger.Level.WARNING, LogCtx.fmt(
                        "SLF4J MDC bridge disabled due to error; SDK continues without MDC. Cause: {0}",
                        t.toString()));
            }
            return NOOP;
        }
    }
}
