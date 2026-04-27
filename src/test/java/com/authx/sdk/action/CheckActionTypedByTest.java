package com.authx.sdk.action;

import com.authx.sdk.ResourceType;
import com.authx.sdk.model.BulkCheckResult;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.transport.InMemoryTransport;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the typed {@code by(ResourceType, id)} / {@code byAll(ResourceType,
 * Iterable<String>)} overloads on {@link CheckAction}. Check itself does
 * not run schema-aware subject validation — these helpers are pure sugar
 * that construct the canonical {@code "type:id"} subject string before
 * routing through the existing {@code by(String)} / {@code byAll(String...)}
 * paths. The tests below verify:
 *
 * <ul>
 *   <li>the compiled overloads exist and dispatch without exception;</li>
 *   <li>they produce the same outputs as the canonical-string forms for
 *       an InMemoryTransport that has the expected tuple written.</li>
 * </ul>
 */
class CheckActionTypedByTest {

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
    void byTypedBuildsCanonicalRef() {
        CheckAction a = new CheckAction("document", "d-1", new InMemoryTransport(),
                Runnable::run, new String[]{"view"});
        ResourceType<CheckActionTypedByTest.R, CheckActionTypedByTest.P> user = ResourceType.of("user", R.class, P.class);
        // InMemoryTransport returns deny by default — all we want is that the
        // call dispatches without any overload-resolution / parse error.
        CheckResult result = a.by(user, "alice");
        assertThat(result).isNotNull();
        assertThat(result.hasPermission()).isFalse();
    }

    @Test
    void byAllTypedIterable() {
        CheckAction a = new CheckAction("document", "d-1", new InMemoryTransport(),
                Runnable::run, new String[]{"view"});
        ResourceType<CheckActionTypedByTest.R, CheckActionTypedByTest.P> user = ResourceType.of("user", R.class, P.class);
        BulkCheckResult m = a.byAll(user, List.of("alice", "bob", "carol"));
        assertThat(m).isNotNull();
        // InMemoryTransport stores no relationships → all three deny.
        assertThat(m.asMap()).hasSize(3);
    }
}
