package com.authx.sdk.spi;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A pluggable health check.
 *
 * <p>A {@code HealthProbe} tests a single aspect of SDK health (gRPC channel reachability,
 * SpiceDB responsiveness, schema availability, cache state, ...) and returns a
 * {@link ProbeResult}. Probes must be side-effect free and must not throw from
 * {@link #check()} — return {@link ProbeResult#down} on failure instead.
 *
 * <p>Probes compose: use {@link #all(HealthProbe...)} or {@link #any(HealthProbe...)}
 * to combine multiple probes. The result of a composite probe preserves the
 * individual sub-results in {@link ProbeResult#children} so callers can still see
 * which sub-probe failed.
 *
 * <p>Implementations shipped in the core SDK:
 * <ul>
 *   <li>{@code com.authx.sdk.health.ChannelStateHealthProbe} — inspects
 *       {@link io.grpc.ManagedChannel#getState(boolean)}, zero-RPC, microsecond latency</li>
 *   <li>{@code com.authx.sdk.health.SchemaReadHealthProbe} — calls
 *       {@code SchemaService.ReadSchema}, schema-independent (tolerates NOT_FOUND)</li>
 *   <li>{@code com.authx.sdk.health.CompositeHealthProbe} — {@link CombineMode#ALL}
 *       / {@link CombineMode#ANY} aggregation</li>
 *   <li>{@code com.authx.sdk.health.NoopHealthProbe} — always up, for in-memory clients</li>
 * </ul>
 *
 * <p>Custom probes (Prometheus push gateway, Kubernetes readiness, filesystem
 * checks, ...) plug in via {@code SdkComponents.builder().healthProbe(myProbe)}.
 */
@FunctionalInterface
public interface HealthProbe {

    /** Execute the probe. Must never throw — encode failures as {@link ProbeResult#down}. */
    ProbeResult check();

    /** Human-readable name used in {@link ProbeResult#name}. Defaults to the class simple name. */
    default String name() {
        return getClass().getSimpleName();
    }

    // ─── Composition ─────────────────────────────────────────────────────

    /**
     * Combine multiple probes so the composite is healthy only if <em>every</em>
     * sub-probe reports healthy. Short-circuits on the first failure is
     * <b>not</b> done — all probes always run so the diagnostic result is complete.
     */
    static HealthProbe all(HealthProbe... probes) {
        return new CompositeProbe(List.of(probes), CombineMode.ALL);
    }

    /**
     * Combine multiple probes so the composite is healthy if <em>at least one</em>
     * sub-probe reports healthy. All probes always run.
     */
    static HealthProbe any(HealthProbe... probes) {
        return new CompositeProbe(List.of(probes), CombineMode.ANY);
    }

    /** A probe that always reports healthy. Intended for in-memory / mock clients. */
    static HealthProbe up() {
        return () -> ProbeResult.up("always-up", Duration.ZERO, "always up");
    }

    /**
     * A probe that always reports unhealthy with the given reason. Useful for
     * forcing a "maintenance mode" or as a sentinel in test fixtures.
     */
    static HealthProbe down(String reason) {
        return () -> ProbeResult.down("always-down", Duration.ZERO, reason);
    }

    // ─── Types ───────────────────────────────────────────────────────────

    /** Aggregation mode for {@link CompositeProbe}. */
    enum CombineMode {
        /** Healthy only if all sub-probes are healthy. */
        ALL,
        /** Healthy if any sub-probe is healthy. */
        ANY
    }

    /**
     * Result of a single probe execution.
     *
     * <p>For leaf probes {@link #children} is empty. For composite probes
     * {@link #children} contains the per-sub-probe results in insertion order,
     * and {@link #healthy} / {@link #latencyNanos} are aggregated.
     *
     * <p><b>Latency precision</b>: stored as nanoseconds in {@link #latencyNanos}
     * to preserve sub-millisecond precision for cheap probes (e.g.
     * {@code ChannelStateHealthProbe} typically returns in microseconds).
     * The legacy {@link #latencyMs()} accessor is still provided for back-compat
     * and is computed as {@code latencyNanos / 1_000_000}, which truncates
     * sub-millisecond probes to {@code 0} as before.
     */
    record ProbeResult(
            String name,
            boolean healthy,
            long latencyNanos,
            String details,
            List<ProbeResult> children
    ) {
        /** Canonical constructor — normalizes {@code children} to an immutable non-null list. */
        public ProbeResult {
            children = (children == null || children.isEmpty())
                    ? List.of()
                    : List.copyOf(children);
        }

        /**
         * Latency in milliseconds, truncated from nanos. Provided for
         * back-compat with the original {@code HealthResult} record. For
         * accurate readings of sub-millisecond probes use {@link #latencyNanos}.
         */
        public long latencyMs() {
            return latencyNanos / 1_000_000L;
        }

        /** Latency as a {@link Duration} (preserves nanosecond precision). */
        public Duration latency() {
            return Duration.ofNanos(latencyNanos);
        }

        /** Create a leaf (no children) result in the UP state. */
        public static ProbeResult up(String name, Duration latency, String details) {
            return new ProbeResult(name, true, latency.toNanos(), details, List.of());
        }

        /** Create a leaf (no children) result in the DOWN state. */
        public static ProbeResult down(String name, Duration latency, String details) {
            return new ProbeResult(name, false, latency.toNanos(), details, List.of());
        }

        /** Create a composite result from sub-results. */
        public static ProbeResult composite(String name, boolean healthy, Duration latency, List<ProbeResult> children) {
            // Joining with a Collector is O(N) string allocation; the previous
            // reduce(a + ", " + b) idiom was O(N²) — fine for 2-3 sub-probes
            // but pathological for large composites.
            String details = children.stream()
                    .map(c -> c.name + "=" + (c.healthy ? "up" : "down"))
                    .collect(Collectors.joining(", "));
            return new ProbeResult(name, healthy, latency.toNanos(), details, children);
        }
    }

    /**
     * Package-private implementation backing {@link #all(HealthProbe...)} and
     * {@link #any(HealthProbe...)}. Kept here (not in the {@code health} package)
     * so the factory methods can stay on the interface itself.
     */
    final class CompositeProbe implements HealthProbe {
        private final List<HealthProbe> probes;
        private final CombineMode mode;

        CompositeProbe(List<HealthProbe> probes, CombineMode mode) {
            if (probes == null || probes.isEmpty()) {
                throw new IllegalArgumentException("composite probe requires at least one sub-probe");
            }
            this.probes = List.copyOf(probes);
            this.mode = mode;
        }

        @Override
        public ProbeResult check() {
            long start = System.nanoTime();
            // Defensive: the HealthProbe contract says check() must never throw,
            // but a misbehaving custom probe could violate that and crash the
            // composite. Wrap each invocation so one bad probe can't take down
            // the whole health check chain.
            List<HealthProbe.ProbeResult> children = probes.stream()
                    .map(this::checkSafely)
                    .toList();
            boolean healthy = switch (mode) {
                case ALL -> children.stream().allMatch(ProbeResult::healthy);
                case ANY -> children.stream().anyMatch(ProbeResult::healthy);
            };
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            return ProbeResult.composite(name(), healthy, elapsed, children);
        }

        private ProbeResult checkSafely(HealthProbe probe) {
            // Resolve the probe's name up-front with its own guard — a probe
            // whose name() method throws must not take down the composite.
            // We use the result as the label on any synthetic down result below.
            final String probeName = safeName(probe);
            long start = System.nanoTime();
            try {
                ProbeResult result = probe.check();
                // Even though the contract says non-null, a misbehaving probe
                // could return null. Convert to a synthetic down result.
                if (result == null) {
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                    return ProbeResult.down(probeName, elapsed, "probe returned null");
                }
                return result;
            } catch (Throwable t) {
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                return ProbeResult.down(probeName, elapsed,
                        "probe threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        private static String safeName(HealthProbe probe) {
            try {
                String n = probe.name();
                return n != null ? n : probe.getClass().getSimpleName();
            } catch (Throwable t) {
                // Last-ditch fallback — never propagate out of the composite.
                return "unnamed-probe";
            }
        }

        @Override
        public String name() {
            return "composite(" + mode.name().toLowerCase() + ")";
        }
    }
}
