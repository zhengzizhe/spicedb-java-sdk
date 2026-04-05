package com.authcses.sdk.event;

import com.authcses.sdk.model.CheckKey;
import com.authcses.sdk.model.Permission;
import com.authcses.sdk.model.ResourceRef;
import com.authcses.sdk.model.SubjectRef;
import com.authcses.sdk.model.enums.SdkAction;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.*;

class TypedEventBusTest {

    @Test void subscribe_specificType() {
        var bus = new DefaultTypedEventBus();
        var received = new ArrayList<SdkTypedEvent.CircuitOpened>();
        bus.subscribe(SdkTypedEvent.CircuitOpened.class, received::add);

        bus.publish(new SdkTypedEvent.CircuitOpened(Instant.now(), "document", new RuntimeException("test")));
        bus.publish(new SdkTypedEvent.CircuitClosed(Instant.now(), "document")); // should NOT be received

        assertThat(received).hasSize(1);
        assertThat(received.get(0).resourceType()).isEqualTo("document");
    }

    @Test void subscribe_all() {
        var bus = new DefaultTypedEventBus();
        var received = new ArrayList<SdkTypedEvent>();
        bus.subscribeAll(received::add);

        bus.publish(new SdkTypedEvent.CircuitOpened(Instant.now(), "document", null));
        bus.publish(new SdkTypedEvent.ClientReady(Instant.now(), Duration.ofMillis(200)));

        assertThat(received).hasSize(2);
    }

    @Test void unsubscribe() {
        var bus = new DefaultTypedEventBus();
        var received = new ArrayList<SdkTypedEvent.CacheHit>();
        var key = CheckKey.of(ResourceRef.of("doc", "1"), Permission.of("view"), SubjectRef.user("a"));
        var reg = bus.subscribe(SdkTypedEvent.CacheHit.class, received::add);

        bus.publish(new SdkTypedEvent.CacheHit(Instant.now(), key));
        assertThat(received).hasSize(1);

        reg.unsubscribe();
        bus.publish(new SdkTypedEvent.CacheHit(Instant.now(), key));
        assertThat(received).hasSize(1); // no new events
    }

    @Test void autoCloseable_registration() throws Exception {
        var bus = new DefaultTypedEventBus();
        var received = new ArrayList<SdkTypedEvent.ClientStopping>();

        try (var reg = bus.subscribe(SdkTypedEvent.ClientStopping.class, received::add)) {
            bus.publish(new SdkTypedEvent.ClientStopping(Instant.now()));
            assertThat(received).hasSize(1);
        }
        // After close, should not receive
        bus.publish(new SdkTypedEvent.ClientStopping(Instant.now()));
        assertThat(received).hasSize(1);
    }

    @Test void listenerException_doesNotBreakOthers() {
        var bus = new DefaultTypedEventBus();
        var received = new ArrayList<SdkTypedEvent.CircuitClosed>();

        bus.subscribe(SdkTypedEvent.CircuitClosed.class, e -> { throw new RuntimeException("bad listener"); });
        bus.subscribe(SdkTypedEvent.CircuitClosed.class, received::add);

        bus.publish(new SdkTypedEvent.CircuitClosed(Instant.now(), "folder"));
        assertThat(received).hasSize(1); // second listener still received
    }

    @Test void transportCallEvent() {
        var bus = new DefaultTypedEventBus();
        var received = new ArrayList<SdkTypedEvent.TransportCall>();
        bus.subscribe(SdkTypedEvent.TransportCall.class, received::add);

        bus.publish(new SdkTypedEvent.TransportCall(
            Instant.now(), SdkAction.CHECK, ResourceRef.of("document", "d1"),
            Duration.ofMillis(5), true));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).action()).isEqualTo(SdkAction.CHECK);
        assertThat(received.get(0).success()).isTrue();
    }

    @Test void sealedInterface_patternMatching() {
        SdkTypedEvent event = new SdkTypedEvent.CircuitOpened(Instant.now(), "document", null);
        String result = switch (event) {
            case SdkTypedEvent.CircuitOpened e -> "opened: " + e.resourceType();
            case SdkTypedEvent.CircuitClosed e -> "closed";
            case SdkTypedEvent.CircuitHalfOpened e -> "half";
            default -> "other";
        };
        assertThat(result).isEqualTo("opened: document");
    }
}
