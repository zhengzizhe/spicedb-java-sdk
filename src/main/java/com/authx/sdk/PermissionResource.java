package com.authx.sdk;

import java.lang.annotation.*;

/**
 * Marks a {@link ResourceFactory} subclass as bound to a SpiceDB resource type.
 *
 * <pre>
 * @PermissionResource("document")
 * public class DocumentPermission extends ResourceFactory {
 *     public boolean canView(String docId, String userId) {
 *         return of(docId).check("view").by(userId).hasPermission();
 *     }
 * }
 *
 * // Register:
 * @Bean DocumentPermission doc(AuthxClient c) { return c.create(DocumentPermission.class); }
 *
 * // Use:
 * @Autowired DocumentPermission doc;
 * doc.canView("doc-1", "alice");
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PermissionResource {
    String value();
}
