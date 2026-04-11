package com.authx.sdk.health;

import com.authx.sdk.spi.HealthProbe;
import com.authzed.api.v1.ReadSchemaRequest;
import com.authzed.api.v1.SchemaServiceGrpc;
import com.authzed.api.v1.SchemaServiceGrpc.SchemaServiceBlockingStub;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end SpiceDB health probe that issues a {@code SchemaService.ReadSchema}
 * RPC and treats both success and {@code NOT_FOUND} as healthy.
 *
 * <p>This is the recommended default probe because it:
 * <ul>
 *   <li><b>Proves reachability + authentication</b> end-to-end (network, TLS,
 *       preshared key, SpiceDB dispatcher, CRDB read path)</li>
 *   <li><b>Does not depend on user schema</b> — works even when the tenant has
 *       no schema written yet ({@code NOT_FOUND} is mapped to healthy because
 *       it means SpiceDB is responding correctly)</li>
 *   <li><b>Is read-only and lightweight</b> — schema blobs are small and cached
 *       on the SpiceDB side</li>
 * </ul>
 *
 * <p>Failure modes mapped to {@code down}:
 * <ul>
 *   <li>{@code UNAVAILABLE} — network / SpiceDB down</li>
 *   <li>{@code UNAUTHENTICATED} — preshared key wrong</li>
 *   <li>{@code PERMISSION_DENIED} — key valid but lacks SchemaService access</li>
 *   <li>{@code DEADLINE_EXCEEDED} — SpiceDB too slow / hung</li>
 *   <li>Any other non-{@code NOT_FOUND} status</li>
 * </ul>
 *
 * <p><b>Default timeout is 500 ms</b> — matched to typical Kubernetes liveness
 * probe budgets (1s probe period with default failure threshold). If your
 * SpiceDB normally responds to ReadSchema in &gt; 500ms (e.g. cold start, very
 * large schema), construct with a larger timeout via the 3-arg constructor.
 */
public final class SchemaReadHealthProbe implements HealthProbe {

    /**
     * Default RPC deadline. 500 ms is short enough that a misbehaving SpiceDB
     * doesn't tarpit our health endpoint, and long enough that p99 latency
     * spikes don't cause spurious "down" reports under normal load.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(500);

    private final ManagedChannel channel;
    private final Duration timeout;
    /**
     * Pre-built stub with the auth interceptor already attached. Reused
     * across {@link #check()} calls — cheaper than rebuilding the interceptor
     * chain on every health probe (which can run many times per minute under
     * actuator polling).
     *
     * <p>Note: the per-call deadline is applied via {@code withDeadlineAfter}
     * on each invocation; that returns a new stub instance but reuses the
     * same underlying channel + interceptors, so it's cheap.
     */
    private final SchemaServiceBlockingStub authedStub;

    /** Construct with the default 500 ms timeout — see class Javadoc. */
    public SchemaReadHealthProbe(ManagedChannel channel, String presharedKey) {
        this(channel, presharedKey, DEFAULT_TIMEOUT);
    }

    public SchemaReadHealthProbe(ManagedChannel channel, String presharedKey, Duration timeout) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(presharedKey, "presharedKey");
        Metadata authMetadata = new Metadata();
        authMetadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + presharedKey);
        // Stub creation happens once; .withInterceptors() returns a stub that
        // shares the channel and is safe to reuse from multiple threads.
        this.authedStub = SchemaServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authMetadata));
    }

    @Override
    public String name() {
        return "spicedb-schema";
    }

    @Override
    public ProbeResult check() {
        long start = System.nanoTime();
        try {
            // .withDeadlineAfter clones the stub with a fresh deadline; the
            // underlying channel + interceptors are shared, so this is cheap.
            authedStub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .readSchema(ReadSchemaRequest.getDefaultInstance());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            return ProbeResult.up(name(), elapsed, "schema present");
        } catch (StatusRuntimeException e) {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            Status.Code code = e.getStatus().getCode();
            // NOT_FOUND = SpiceDB is up and responding, just has no schema yet → healthy.
            if (code == Status.Code.NOT_FOUND) {
                return ProbeResult.up(name(), elapsed, "reachable (no schema written)");
            }
            // getDescription() is nullable — fall back to "(no description)" so
            // the message never reads as the confusing literal "msg=null".
            String desc = e.getStatus().getDescription();
            return ProbeResult.down(name(), elapsed,
                    "code=" + code + " msg=" + (desc != null ? desc : "(no description)"));
        } catch (Exception e) {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            return ProbeResult.down(name(), elapsed, "exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
