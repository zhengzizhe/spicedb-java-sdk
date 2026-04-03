package com.authcses.sdk.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SdkEventBusTest {

    private SdkEventBus bus;

    @BeforeEach
    void setup() {
        bus = new SdkEventBus();
    }

    @Test
    void on_receivesMatchingEvents() {
        List<String> received = new ArrayList<>();
        bus.on(SdkEvent.CIRCUIT_OPENED, e -> received.add(e.message()));

        bus.fire(SdkEvent.CIRCUIT_OPENED, "5 failures");
        bus.fire(SdkEvent.CIRCUIT_CLOSED, "recovered");  // should not trigger

        assertEquals(1, received.size());
        assertEquals("5 failures", received.getFirst());
    }

    @Test
    void onAll_receivesAllEvents() {
        List<SdkEvent> received = new ArrayList<>();
        bus.onAll(e -> received.add(e.event()));

        bus.fire(SdkEvent.CONNECTED, "ok");
        bus.fire(SdkEvent.CIRCUIT_OPENED, "fail");
        bus.fire(SdkEvent.CACHE_EVICTION, "evicted");

        assertEquals(3, received.size());
    }

    @Test
    void multipleListeners_allCalled() {
        AtomicInteger count = new AtomicInteger(0);
        bus.on(SdkEvent.CONNECTED, e -> count.incrementAndGet());
        bus.on(SdkEvent.CONNECTED, e -> count.incrementAndGet());
        bus.on(SdkEvent.CONNECTED, e -> count.incrementAndGet());

        bus.fire(SdkEvent.CONNECTED, "ok");
        assertEquals(3, count.get());
    }

    @Test
    void listenerException_doesNotPropagateOrAffectOthers() {
        List<String> received = new ArrayList<>();
        bus.on(SdkEvent.CONNECTED, e -> { throw new RuntimeException("boom"); });
        bus.on(SdkEvent.CONNECTED, e -> received.add("second"));

        assertDoesNotThrow(() -> bus.fire(SdkEvent.CONNECTED, "test"));
        assertEquals(1, received.size());  // second listener still called
    }

    @Test
    void off_removesListener() {
        AtomicInteger count = new AtomicInteger(0);
        SdkEventListener listener = e -> count.incrementAndGet();

        bus.on(SdkEvent.CONNECTED, listener);
        bus.fire(SdkEvent.CONNECTED, "1");
        assertEquals(1, count.get());

        bus.off(SdkEvent.CONNECTED, listener);
        bus.fire(SdkEvent.CONNECTED, "2");
        assertEquals(1, count.get());  // not incremented
    }

    @Test
    void eventData_carriesAttributes() {
        bus.on(SdkEvent.SCHEMA_UPDATED, e -> {
            assertEquals(2, (int) e.<Integer>attribute("endpointCount"));
            assertEquals("key changed", e.message());
        });

        bus.fire(SdkEvent.SCHEMA_UPDATED, "key changed",
                java.util.Map.of("endpointCount", 2));
    }

    @Test
    void concurrentFireAndSubscribe_threadSafe() throws InterruptedException {
        AtomicInteger count = new AtomicInteger(0);
        bus.on(SdkEvent.CONNECTED, e -> count.incrementAndGet());

        int threads = 50;
        int firesPerThread = 1000;
        var latch = new CountDownLatch(threads);
        var pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < firesPerThread; i++) {
                    bus.fire(SdkEvent.CONNECTED, "concurrent");
                }
                latch.countDown();
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(threads * firesPerThread, count.get());
    }

    @Test
    void clientIntegration_eventsOnCloseLifecycle() {
        var client = com.authcses.sdk.AuthCsesClient.inMemory();
        List<SdkEvent> events = new ArrayList<>();
        client.eventBus().onAll(e -> events.add(e.event()));

        client.close();

        assertTrue(events.contains(SdkEvent.CLIENT_STOPPING));
        assertTrue(events.contains(SdkEvent.CLIENT_STOPPED));
    }
}
