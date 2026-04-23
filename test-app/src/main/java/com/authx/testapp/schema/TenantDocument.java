package com.authx.testapp.schema;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectType;

import java.util.Arrays;
import java.util.List;

/**
 * Typed enums for SpiceDB resource type <b>tenant_document</b> — a
 * multi-tenant-safe document model where every permission is gated
 * behind an {@code org->access} intersection.
 *
 * <p>Key design: {@code permission view = (viewer + ...) & org->access - banned}.
 * The intersection means a user can lose access <b>without any tuple
 * deletion</b> — simply leaving the org flips every derived permission
 * on every tenant_document in that org to NO.
 *
 * <p>Mirrors codegen output style; kept as a hand-written companion to
 * the {@code tenant_document} definition in {@code deploy/schema.zed}.
 */
public final class TenantDocument {

    /** Relations — used with grant / revoke on the typed chain. */
    public enum Rel implements Relation.Named {
        ORG("org", "organization"),
        OWNER("owner", "user"),
        EDITOR("editor", "user", "group#member"),
        VIEWER("viewer", "user", "group#member", "user:*"),
        BANNED("banned", "user");

        private final String value;
        private final List<SubjectType> subjectTypes;

        Rel(String v, String... sts) {
            this.value = v;
            this.subjectTypes = Arrays.stream(sts).map(SubjectType::parse).toList();
        }

        @Override public String relationName() { return value; }
        @Override public List<SubjectType> subjectTypes() { return subjectTypes; }
    }

    /** Permissions — used with check / who / findBy on the typed chain. */
    public enum Perm implements Permission.Named {
        VIEW("view"),
        EDIT("edit"),
        MANAGE("manage");

        private final String value;
        Perm(String v) { this.value = v; }
        @Override public String permissionName() { return value; }
    }

    private TenantDocument() {}
}
