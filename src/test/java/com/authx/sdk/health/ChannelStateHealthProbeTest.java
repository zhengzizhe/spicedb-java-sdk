package com.authx.sdk.health;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ChannelStateHealthProbe}. Uses in-process gRPC channels to
 * drive the channel through its various states.
 */
class ChannelStateHealthProbeTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @Test
    void idleChannel_reportsHealthy() {
        // A freshly built in-process channel with no server is in IDLE state until
        // a real RPC is attempted. IDLE is treated as healthy because gRPC will
        // lazily connect on first use.
        String name = InProcessServerBuilder.generateName();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var probe = new ChannelStateHealthProbe(channel);
        var result = probe.check();

        assertThat(result.healthy()).isTrue();
        assertThat(result.details()).contains("IDLE");
    }

    @Test
    void shutdownChannel_reportsUnhealthy() {
        String name = InProcessServerBuilder.generateName();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        channel.shutdown();

        var probe = new ChannelStateHealthProbe(channel);
        var result = probe.check();

        assertThat(result.healthy()).isFalse();
        assertThat(result.details()).contains("SHUTDOWN");
    }

    @Test
    void customName_reportedInResult() {
        String name = InProcessServerBuilder.generateName();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var probe = new ChannelStateHealthProbe(channel, "my-custom-probe");
        var result = probe.check();

        assertThat(result.name()).isEqualTo("my-custom-probe");
        assertThat(probe.name()).isEqualTo("my-custom-probe");
    }
}
