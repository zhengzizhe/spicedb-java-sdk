package com.authx.sdk.health;

import com.authx.sdk.spi.HealthProbe;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Zero-RPC health probe that inspects the local gRPC channel state.
 *
 * <p>Reports healthy when the channel is in {@link ConnectivityState#READY} or
 * {@link ConnectivityState#IDLE} (IDLE counts as healthy because gRPC's default
 * policy is lazy — an IDLE channel will connect on the next RPC). Reports
 * unhealthy on {@link ConnectivityState#TRANSIENT_FAILURE} or
 * {@link ConnectivityState#SHUTDOWN}; {@link ConnectivityState#CONNECTING} is
 * treated as <em>unhealthy</em> because it indicates the SDK has not yet
 * successfully spoken to SpiceDB.
 *
 * <p>Latency is sub-microsecond — this probe does not perform any RPC. Use it
 * as a cheap sanity check alongside an actual RPC-based probe
 * (e.g. {@link SchemaReadHealthProbe}).
 */
public final class ChannelStateHealthProbe implements HealthProbe {

    /** Channel states that count as "healthy". */
    private static final Set<ConnectivityState> HEALTHY_STATES =
            Set.of(ConnectivityState.READY, ConnectivityState.IDLE);

    private final ManagedChannel channel;
    private final String name;

    public ChannelStateHealthProbe(ManagedChannel channel) {
        this(channel, "grpc-channel-state");
    }

    public ChannelStateHealthProbe(ManagedChannel channel, String name) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.name = Objects.requireNonNull(name, "name");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ProbeResult check() {
        long start = System.nanoTime();
        // getState(false) = do NOT trigger a connection attempt; just report.
        ConnectivityState state = channel.getState(false);
        Duration latency = Duration.ofNanos(System.nanoTime() - start);
        String details = "state=" + state;
        return HEALTHY_STATES.contains(state)
                ? ProbeResult.up(name, latency, details)
                : ProbeResult.down(name, latency, details);
    }
}
