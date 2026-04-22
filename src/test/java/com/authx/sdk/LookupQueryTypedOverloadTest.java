package com.authx.sdk;

import com.authx.sdk.action.WhoBuilder;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.transport.InMemoryTransport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the typed {@link ResourceType}-based sugar on the lookup /
 * who / findBy entry points. These overloads let business code avoid
 * hand-building {@code "type:id"} canonical strings when they already
 * hold the typed subject type descriptor.
 *
 * <p>The underlying RPC contract is unchanged — the tests here verify
 * that the overloads exist, dispatch without surprise, and produce
 * observable behaviour (empty results against an in-memory transport
 * with no tuples loaded).
 */
class LookupQueryTypedOverloadTest {

    enum R implements Relation.Named {
        VIEWER("viewer");
        private final String v;
        R(String v) { this.v = v; }
        @Override public String relationName() { return v; }
    }

    enum P implements Permission.Named {
        VIEW("view");
        private final String v;
        P(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    @Test
    void lookupByTypedBuildsCanonicalRef() {
        var client = AuthxClient.inMemory();
        var userType = ResourceType.of("user", R.class, P.class);
        List<String> ids = client.lookup("document")
                .withPermission("view")
                .by(userType, "alice")
                .fetch();
        assertThat(ids).isEmpty(); // no tuples loaded
    }

    @Test
    void handleWhoTypedBuildsLookup() {
        var client = AuthxClient.inMemory();
        var userType = ResourceType.of("user", R.class, P.class);
        // who(ResourceType) unwraps to the string form — verify the returned
        // builder is non-null and can produce a subject query.
        WhoBuilder w = client.on("document").resource("d-1").who(userType);
        assertThat(w).isNotNull();
        var subjects = w.withPermission("view").fetch();
        assertThat(subjects).isEmpty();
    }
}
