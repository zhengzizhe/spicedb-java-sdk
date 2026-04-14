package com.authx.clustertest.correctness;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;

import java.util.UUID;
import java.util.function.Function;

/**
 * C3: user joins group; group granted member of space; user can access space.
 *
 * <p>Schema (v2): {@code space.member: user | group#member | ...} — so we
 * grant the space.member relation to the subject {@code group:G#member}, then
 * check the user's {@code space.view} permission.
 */
public class C3GroupInheritance implements Function<AuthxClient, String> {
    @Override
    public String apply(AuthxClient client) {
        String suffix  = UUID.randomUUID().toString();
        String userId  = "c3-user-"  + suffix;
        String groupId = "c3-group-" + suffix;
        String spaceId = "c3-space-" + suffix;

        // user ∈ group
        client.on("group").resource(groupId).grant("member").to(userId);

        // group#member ∈ space.member
        client.on("space").resource(spaceId).grant("member")
                .toSubjects("group:" + groupId + "#member");

        boolean ok = client.on("space").resource(spaceId)
                .check("view")
                .withConsistency(Consistency.full())
                .by(userId)
                .hasPermission();

        return ok ? null : "expected space.view=true via group membership";
    }
}
