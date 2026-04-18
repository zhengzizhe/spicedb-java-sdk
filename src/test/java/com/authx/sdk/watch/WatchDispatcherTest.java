package com.authx.sdk.watch;

import com.authx.sdk.model.RelationshipChange;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link WatchDispatcher} — routing watch events to strategies and global listeners.
 */
class WatchDispatcherTest {

    private static RelationshipChange touchChange(String resourceType, String resourceId) {
        return new RelationshipChange(
                RelationshipChange.Operation.TOUCH,
                resourceType, resourceId,
                "editor", "user", "alice", null,
                "zed_token_1", null, null, Map.of());
    }

    private static RelationshipChange deleteChange(String resourceType, String resourceId) {
        return new RelationshipChange(
                RelationshipChange.Operation.DELETE,
                resourceType, resourceId,
                "editor", "user", "alice", null,
                "zed_token_2", null, null, Map.of());
    }

    @Test
    void dispatchesToMatchingStrategy() {
        var received = new ArrayList<RelationshipChange>();
        WatchStrategy docStrategy = new WatchStrategy() {
            @Override
            public String resourceType() { return "document"; }

            @Override
            public void onTouch(RelationshipChange change) {
                received.add(change);
            }
        };

        var dispatcher = new WatchDispatcher(List.of(docStrategy));
        dispatcher.accept(touchChange("document", "d1"));

        assertThat(received).hasSize(1);
        assertThat(received.getFirst().resourceId()).isEqualTo("d1");
    }

    @Test
    void doesNotDispatchToNonMatchingStrategy() {
        var received = new ArrayList<RelationshipChange>();
        WatchStrategy folderStrategy = new WatchStrategy() {
            @Override
            public String resourceType() { return "folder"; }

            @Override
            public void onTouch(RelationshipChange change) {
                received.add(change);
            }
        };

        var dispatcher = new WatchDispatcher(List.of(folderStrategy));
        dispatcher.accept(touchChange("document", "d1"));

        assertThat(received).isEmpty();
    }

    @Test
    void dispatchesTouchAndDeleteCorrectly() {
        var touchEvents = new ArrayList<RelationshipChange>();
        var deleteEvents = new ArrayList<RelationshipChange>();

        WatchStrategy strategy = new WatchStrategy() {
            @Override
            public String resourceType() { return "document"; }

            @Override
            public void onTouch(RelationshipChange change) {
                touchEvents.add(change);
            }

            @Override
            public void onDelete(RelationshipChange change) {
                deleteEvents.add(change);
            }
        };

        var dispatcher = new WatchDispatcher(List.of(strategy));
        dispatcher.accept(touchChange("document", "d1"));
        dispatcher.accept(deleteChange("document", "d2"));

        assertThat(touchEvents).hasSize(1);
        assertThat(deleteEvents).hasSize(1);
        assertThat(touchEvents.getFirst().resourceId()).isEqualTo("d1");
        assertThat(deleteEvents.getFirst().resourceId()).isEqualTo("d2");
    }

    @Test
    void multipleStrategiesForSameResourceType() {
        var received1 = new ArrayList<RelationshipChange>();
        var received2 = new ArrayList<RelationshipChange>();

        WatchStrategy s1 = new WatchStrategy() {
            @Override
            public String resourceType() { return "document"; }

            @Override
            public void onTouch(RelationshipChange change) { received1.add(change); }
        };

        WatchStrategy s2 = new WatchStrategy() {
            @Override
            public String resourceType() { return "document"; }

            @Override
            public void onTouch(RelationshipChange change) { received2.add(change); }
        };

        var dispatcher = new WatchDispatcher(List.of(s1, s2));
        dispatcher.accept(touchChange("document", "d1"));

        assertThat(received1).hasSize(1);
        assertThat(received2).hasSize(1);
    }

    @Test
    void globalListenerReceivesAllEvents() {
        var globalEvents = new ArrayList<RelationshipChange>();

        var dispatcher = new WatchDispatcher(List.of());
        dispatcher.addGlobalListener(globalEvents::add);

        dispatcher.accept(touchChange("document", "d1"));
        dispatcher.accept(touchChange("folder", "f1"));
        dispatcher.accept(deleteChange("document", "d2"));

        assertThat(globalEvents).hasSize(3);
    }

    @Test
    void removeGlobalListenerStopsDelivery() {
        var globalEvents = new ArrayList<RelationshipChange>();
        Consumer<RelationshipChange> listener = globalEvents::add;

        var dispatcher = new WatchDispatcher(List.of());
        dispatcher.addGlobalListener(listener);
        dispatcher.accept(touchChange("document", "d1"));

        dispatcher.removeGlobalListener(listener);
        dispatcher.accept(touchChange("document", "d2"));

        assertThat(globalEvents).hasSize(1);
    }

    @Test
    void strategyExceptionDoesNotBreakOtherStrategies() {
        var received = new ArrayList<RelationshipChange>();

        WatchStrategy failingStrategy = new WatchStrategy() {
            @Override
            public String resourceType() { return "document"; }

            @Override
            public void onTouch(RelationshipChange change) {
                throw new RuntimeException("strategy error");
            }
        };

        WatchStrategy workingStrategy = new WatchStrategy() {
            @Override
            public String resourceType() { return "document"; }

            @Override
            public void onTouch(RelationshipChange change) {
                received.add(change);
            }
        };

        var dispatcher = new WatchDispatcher(List.of(failingStrategy, workingStrategy));
        dispatcher.accept(touchChange("document", "d1"));

        // Working strategy still receives the event
        assertThat(received).hasSize(1);
    }

    @Test
    void globalListenerExceptionDoesNotBreakOtherListeners() {
        var received = new ArrayList<RelationshipChange>();

        var dispatcher = new WatchDispatcher(List.of());
        dispatcher.addGlobalListener(change -> { throw new RuntimeException("listener error"); });
        dispatcher.addGlobalListener(received::add);

        dispatcher.accept(touchChange("document", "d1"));

        assertThat(received).hasSize(1);
    }

    @Test
    void emptyStrategiesAndListenersDoNotCrash() {
        var dispatcher = new WatchDispatcher(List.of());
        dispatcher.accept(touchChange("document", "d1"));
        // No exception = success
    }

    @Test
    void strategyOnChangeDefaultRoutesTouchAndDelete() {
        var touchEvents = new ArrayList<RelationshipChange>();
        var deleteEvents = new ArrayList<RelationshipChange>();

        // Use WatchStrategy with only onTouch/onDelete overridden (default onChange routes)
        WatchStrategy strategy = new WatchStrategy() {
            @Override
            public String resourceType() { return "document"; }

            @Override
            public void onTouch(RelationshipChange change) { touchEvents.add(change); }

            @Override
            public void onDelete(RelationshipChange change) { deleteEvents.add(change); }
        };

        // Directly call onChange (which is what WatchDispatcher calls)
        strategy.onChange(touchChange("document", "d1"));
        strategy.onChange(deleteChange("document", "d2"));

        assertThat(touchEvents).hasSize(1);
        assertThat(deleteEvents).hasSize(1);
    }
}
