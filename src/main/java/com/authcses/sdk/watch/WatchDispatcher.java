package com.authcses.sdk.watch;

import com.authcses.sdk.model.RelationshipChange;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Internal dispatcher: routes Watch events to registered strategies by resource type.
 * Also supports raw global listeners for cross-cutting concerns (audit, logging).
 */
public class WatchDispatcher implements Consumer<RelationshipChange> {

    private static final System.Logger LOG = System.getLogger(WatchDispatcher.class.getName());

    private final Map<String, List<WatchStrategy>> strategyMap;
    private final List<Consumer<RelationshipChange>> globalListeners = new CopyOnWriteArrayList<>();

    public WatchDispatcher(List<WatchStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.groupingBy(WatchStrategy::resourceType));
    }

    /** Register a global listener (receives ALL types, for audit/logging). */
    public void addGlobalListener(Consumer<RelationshipChange> listener) {
        globalListeners.add(listener);
    }

    public void removeGlobalListener(Consumer<RelationshipChange> listener) {
        globalListeners.remove(listener);
    }

    @Override
    public void accept(RelationshipChange change) {
        // 1. Dispatch to type-specific strategies
        var strategies = strategyMap.get(change.resourceType());
        if (strategies != null) {
            for (var strategy : strategies) {
                try {
                    strategy.onChange(change);
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.WARNING,
                            "WatchStrategy [{0}] error on {1}:{2}: {3}",
                            strategy.getClass().getSimpleName(),
                            change.resourceType(), change.resourceId(),
                            e.getMessage());
                }
            }
        }

        // 2. Dispatch to global listeners
        for (var listener : globalListeners) {
            try {
                listener.accept(change);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Global watch listener error: {0}", e.getMessage());
            }
        }
    }
}
