package com.authx.sdk.event;

import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.enums.SdkAction;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class EventAdditionalTest {

    // ---- DefaultTypedEventBus edge cases ----
    @Nested class DefaultTypedEventBusEdgeCases {
        @Test void publishWithNoSubscribers_doesNotThrow() {
            var bus = new DefaultTypedEventBus();
            assertThatNoException().isThrownBy(() ->
                bus.publish(new SdkTypedEvent.ClientReady(Instant.now(), Duration.ofMillis(100))));
        }

        @Test void multipleSubscribersForSameType() {
            var bus = new DefaultTypedEventBus();
            var list1 = new ArrayList<SdkTypedEvent.CircuitClosed>();
            var list2 = new ArrayList<SdkTypedEvent.CircuitClosed>();
            bus.subscribe(SdkTypedEvent.CircuitClosed.class, list1::add);
            bus.subscribe(SdkTypedEvent.CircuitClosed.class, list2::add);

            bus.publish(new SdkTypedEvent.CircuitClosed(Instant.now(), "doc"));
            assertThat(list1).hasSize(1);
            assertThat(list2).hasSize(1);
        }

        @Test void globalListenerReceivesAfterTypeSpecific() {
            var bus = new DefaultTypedEventBus();
            var global = new ArrayList<SdkTypedEvent>();
            var specific = new ArrayList<SdkTypedEvent.ClientStopped>();
            bus.subscribe(SdkTypedEvent.ClientStopped.class, specific::add);
            bus.subscribeAll(global::add);

            bus.publish(new SdkTypedEvent.ClientStopped(Instant.now()));
            assertThat(specific).hasSize(1);
            assertThat(global).hasSize(1);
        }

        @Test void asyncPublisher() throws Exception {
            var executor = Executors.newSingleThreadExecutor();
            try {
                var bus = new DefaultTypedEventBus(executor);
                var latch = new CountDownLatch(1);
                bus.subscribe(SdkTypedEvent.WatchConnected.class, e -> latch.countDown());

                bus.publish(new SdkTypedEvent.WatchConnected(Instant.now()));
                assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdown();
            }
        }

        @Test void globalListenerExceptionDoesNotBreakOthers() {
            var bus = new DefaultTypedEventBus();
            var received = new ArrayList<SdkTypedEvent>();
            bus.subscribeAll(e -> { throw new RuntimeException("bad global"); });
            bus.subscribeAll(received::add);

            bus.publish(new SdkTypedEvent.ClientStopping(Instant.now()));
            assertThat(received).hasSize(1);
        }

        @Test void unsubscribeAll() {
            var bus = new DefaultTypedEventBus();
            var received = new ArrayList<SdkTypedEvent>();
            var reg = bus.subscribeAll(received::add);

            bus.publish(new SdkTypedEvent.ClientStopping(Instant.now()));
            assertThat(received).hasSize(1);

            reg.unsubscribe();
            bus.publish(new SdkTypedEvent.ClientStopping(Instant.now()));
            assertThat(received).hasSize(1); // no new events
        }

        @Test void nullExecutorFallsBackToSync() {
            var bus = new DefaultTypedEventBus(null);
            var received = new ArrayList<SdkTypedEvent.ClientReady>();
            bus.subscribe(SdkTypedEvent.ClientReady.class, received::add);

            bus.publish(new SdkTypedEvent.ClientReady(Instant.now(), Duration.ofMillis(50)));
            assertThat(received).hasSize(1);
        }
    }

    // ---- SdkTypedEvent record construction ----
    @Nested class SdkTypedEventTest {
        @Test void allEventTypesHaveTimestamp() {
            var now = Instant.now();
            assertThat(new SdkTypedEvent.ClientReady(now, Duration.ZERO).timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.ClientStopping(now).timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.ClientStopped(now).timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.CircuitOpened(now, "doc", null).timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.CircuitClosed(now, "doc").timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.CircuitHalfOpened(now, "doc").timestamp()).isEqualTo(now);

            var key = CheckKey.of(ResourceRef.of("doc", "1"), Permission.of("view"), SubjectRef.user("a"));
            assertThat(new SdkTypedEvent.CacheHit(now, key).timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.CacheMiss(now, key).timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.CacheEviction(now, key, "expired").timestamp()).isEqualTo(now);

            assertThat(new SdkTypedEvent.TransportCall(now, SdkAction.CHECK, ResourceRef.of("doc", "1"),
                Duration.ofMillis(5), true).timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.WatchConnected(now).timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.WatchDisconnected(now, "reset").timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.SchemaRefreshed(now, 3).timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.SchemaLoadFailed(now, new RuntimeException("x")).timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.RateLimited(now, "CHECK").timestamp()).isEqualTo(now);
            assertThat(new SdkTypedEvent.BulkheadRejected(now, "CHECK").timestamp()).isEqualTo(now);
        }

        @Test void transportCallFields() {
            var now = Instant.now();
            var event = new SdkTypedEvent.TransportCall(now, SdkAction.WRITE,
                ResourceRef.of("folder", "f1"), Duration.ofMillis(10), false);
            assertThat(event.action()).isEqualTo(SdkAction.WRITE);
            assertThat(event.resource()).isEqualTo(ResourceRef.of("folder", "f1"));
            assertThat(event.latency()).isEqualTo(Duration.ofMillis(10));
            assertThat(event.success()).isFalse();
        }
    }
}
