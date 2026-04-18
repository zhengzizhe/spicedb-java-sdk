package com.authx.sdk.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link WatchConnectionState} — connection state tracking enum.
 */
class WatchConnectionStateTest {

    @Test
    void allStatesExist() {
        assertThat(WatchConnectionState.values()).containsExactly(
                WatchConnectionState.NOT_STARTED,
                WatchConnectionState.CONNECTING,
                WatchConnectionState.CONNECTED,
                WatchConnectionState.RECONNECTING,
                WatchConnectionState.STOPPED);
    }

    @Test
    void valueOfWorks() {
        assertThat(WatchConnectionState.valueOf("NOT_STARTED")).isEqualTo(WatchConnectionState.NOT_STARTED);
        assertThat(WatchConnectionState.valueOf("CONNECTING")).isEqualTo(WatchConnectionState.CONNECTING);
        assertThat(WatchConnectionState.valueOf("CONNECTED")).isEqualTo(WatchConnectionState.CONNECTED);
        assertThat(WatchConnectionState.valueOf("RECONNECTING")).isEqualTo(WatchConnectionState.RECONNECTING);
        assertThat(WatchConnectionState.valueOf("STOPPED")).isEqualTo(WatchConnectionState.STOPPED);
    }

    @Test
    void invalidValueThrows() {
        assertThatThrownBy(() -> WatchConnectionState.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
