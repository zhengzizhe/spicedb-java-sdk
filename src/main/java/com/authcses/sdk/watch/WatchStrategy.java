package com.authcses.sdk.watch;

import com.authcses.sdk.model.RelationshipChange;

/**
 * Strategy for handling real-time relationship changes on a specific resource type.
 * Implement one per resource type and register via Builder.
 *
 * <pre>
 * public class DocumentWatchStrategy implements WatchStrategy {
 *
 *     private final NotifyService notify;
 *     private final CacheService cache;
 *
 *     public DocumentWatchStrategy(NotifyService notify, CacheService cache) {
 *         this.notify = notify;
 *         this.cache = cache;
 *     }
 *
 *     {@literal @}Override public String resourceType() { return "document"; }
 *
 *     {@literal @}Override
 *     public void onTouch(RelationshipChange change) {
 *         cache.invalidate("doc:" + change.resourceId());
 *         if ("editor".equals(change.relation())) {
 *             notify.send(change.subjectId(), "你被授予了编辑权限");
 *         }
 *     }
 *
 *     {@literal @}Override
 *     public void onDelete(RelationshipChange change) {
 *         cache.invalidate("doc:" + change.resourceId());
 *     }
 * }
 *
 * // Register
 * AuthCsesClient.builder()
 *     .extend(e -> e
 *         .addWatchStrategy(new DocumentWatchStrategy(notify, cache))
 *         .addWatchStrategy(new FolderWatchStrategy(cache)))
 *     .build();
 * </pre>
 */
public interface WatchStrategy {

    /** The resource type this strategy handles (e.g., "document", "folder"). */
    String resourceType();

    /** Called when a relationship is created or updated (TOUCH). */
    default void onTouch(RelationshipChange change) {}

    /** Called when a relationship is deleted (DELETE). */
    default void onDelete(RelationshipChange change) {}

    /** Called for all changes. Default dispatches to onTouch/onDelete. Override for custom routing. */
    default void onChange(RelationshipChange change) {
        switch (change.operation()) {
            case TOUCH -> onTouch(change);
            case DELETE -> onDelete(change);
        }
    }
}
