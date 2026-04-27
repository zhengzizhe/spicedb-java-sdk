package com.authx.sdk.lifecycle;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.event.SdkTypedEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleManagerTest {

    @Test
    void phases_recordDurations() {
        com.authx.sdk.event.DefaultTypedEventBus bus = new DefaultTypedEventBus();
        com.authx.sdk.lifecycle.LifecycleManager lm = new LifecycleManager(bus);

        lm.begin();
        assertEquals(SdkState.STARTING, lm.state());

        lm.phase(SdkPhase.CHANNEL, () -> sleep(10));
        lm.phase(SdkPhase.TRANSPORT, () -> sleep(5));
        lm.complete();

        assertEquals(SdkState.RUNNING, lm.state());
        assertTrue(lm.isReady());
        assertTrue(lm.isHealthy());
        assertTrue(lm.totalStartupMs() >= 10);

        java.util.Map<com.authx.sdk.lifecycle.SdkPhase,java.lang.Long> durations = lm.phaseDurations();
        assertTrue(durations.containsKey(SdkPhase.CHANNEL));
        assertTrue(durations.containsKey(SdkPhase.CHANNEL));
        assertTrue(durations.get(SdkPhase.CHANNEL) >= 10);
    }

    @Test
    void startupReport_formatted() {
        com.authx.sdk.lifecycle.LifecycleManager lm = new LifecycleManager(new DefaultTypedEventBus());
        lm.begin();
        lm.phase(SdkPhase.CHANNEL, () -> {});
        lm.phase(SdkPhase.SCHEMA, () -> {});
        lm.complete();

        String report = lm.startupReport();
        assertTrue(report.contains("channel="));
        assertTrue(report.contains("schema="));
    }

    @Test
    void complete_firesClientReadyEvent() {
        com.authx.sdk.event.DefaultTypedEventBus bus = new DefaultTypedEventBus();
        List<SdkTypedEvent> events = new ArrayList<>();
        bus.subscribeAll(events::add);

        com.authx.sdk.lifecycle.LifecycleManager lm = new LifecycleManager(bus);
        lm.begin();
        lm.complete();

        assertTrue(events.stream().anyMatch(e -> e instanceof SdkTypedEvent.ClientReady));
    }

    @Test
    void degraded_and_recovered() {
        com.authx.sdk.lifecycle.LifecycleManager lm = new LifecycleManager(new DefaultTypedEventBus());
        lm.begin();
        lm.complete();

        assertEquals(SdkState.RUNNING, lm.state());
        assertTrue(lm.isReady());
        assertTrue(lm.isHealthy());

        lm.degraded("Watch disconnected");
        assertEquals(SdkState.DEGRADED, lm.state());
        assertTrue(lm.isReady());      // still operational
        assertFalse(lm.isHealthy());   // not fully healthy

        lm.recovered();
        assertEquals(SdkState.RUNNING, lm.state());
        assertTrue(lm.isHealthy());
    }

    @Test
    void stopping_stopped() {
        com.authx.sdk.lifecycle.LifecycleManager lm = new LifecycleManager(new DefaultTypedEventBus());
        lm.begin();
        lm.complete();

        lm.stopping();
        assertEquals(SdkState.STOPPING, lm.state());
        assertFalse(lm.isReady());

        lm.stopped();
        assertEquals(SdkState.STOPPED, lm.state());
    }

    @Test
    void phaseFailed_propagatesException() {
        com.authx.sdk.lifecycle.LifecycleManager lm = new LifecycleManager(new DefaultTypedEventBus());
        lm.begin();

        assertThrows(RuntimeException.class, () ->
                lm.phase(SdkPhase.CHANNEL, () -> { throw new RuntimeException("connection refused"); }));

        // Duration still recorded for the failed phase
        assertTrue(lm.phaseDurations().containsKey(SdkPhase.CHANNEL));
    }

    @Test
    void inMemoryClient_isReady() {
        com.authx.sdk.AuthxClient client = AuthxClient.inMemory();
        assertTrue(client.lifecycle().isReady());
        assertEquals(SdkState.RUNNING, client.lifecycle().state());
        client.close();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
