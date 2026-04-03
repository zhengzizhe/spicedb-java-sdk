package com.authcses.sdk.entity;

import java.lang.annotation.*;

/**
 * Marks a class as a permission entity bound to a SpiceDB resource type.
 *
 * <pre>
 * @ResourceType("document")
 * public class DocumentPermission extends PermissionEntity {
 *     public boolean canView(String docId, String userId) {
 *         return of(docId).check("view").by(userId).hasPermission();
 *     }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ResourceType {
    /** SpiceDB resource type name (e.g., "document", "folder", "group"). */
    String value();
}
