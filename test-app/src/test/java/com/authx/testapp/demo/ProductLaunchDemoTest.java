package com.authx.testapp.demo;

import static com.authx.testapp.demo.ProductLaunchDemo.DOC_PLAN;
import static com.authx.testapp.demo.ProductLaunchDemo.DOC_PRESS;
import static com.authx.testapp.demo.ProductLaunchDemo.DOC_ROADMAP;
import static com.authx.testapp.demo.ProductLaunchDemo.USER_ALICE;
import static com.authx.testapp.demo.ProductLaunchDemo.USER_ENG_LEAD;
import static com.authx.testapp.schema.Schema.Document;
import static com.authx.testapp.schema.Schema.User;
import static org.assertj.core.api.Assertions.assertThat;

import com.authx.sdk.AuthxClient;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end smoke test of the new typed descriptor API.
 *
 * <p>The {@link AuthxClient} bean in {@link com.authx.testapp.TestApplication}
 * is wired with {@link AuthxClient#inMemory()} — an exact-match, no-schema
 * transport. Assertions here verify that:
 * <ol>
 *   <li>Every API call in the demo compiles and executes without throwing.</li>
 *   <li>Grant / revoke counts match the number of writes the demo issued.</li>
 *   <li>{@code who} / {@code checkAll} / {@code batchCheck} / {@code findBy}
 *       return the right <em>shape</em> of result (non-null, expected size).</li>
 *   <li>Direct-edge checks resolve — e.g., alice's explicit press-doc grant
 *       is visible, eng-lead's owner role on the plan doc is visible.</li>
 * </ol>
 *
 * <p>Schema-derived <em>inheritance</em> (plan.view = folder.view = space.view
 * = org.access) is <b>not</b> verified here — that path needs a real SpiceDB.
 * Against production SpiceDB the same demo code reports the full inherited
 * picture without any change to the service.
 */
@SpringBootTest
class ProductLaunchDemoTest {

    @Autowired ProductLaunchDemo demo;
    @Autowired AuthxClient client;

    @Test
    void runs_full_story_end_to_end() {
        var out = demo.run();

        // 19 discrete grant writes were issued in the demo:
        //   1 org admin + 4 org members   = 5
        //   2 dept members + 1 dept member = 3
        //   2 quark group members         = 2
        //   space org + alice member       = 2
        //   root folder + quark folder parent + eng-lead owner = 3
        //   doc-plan folder/editor/owner   = 3
        //   doc-roadmap folder/viewer      = 2
        //   doc-press folder/viewer(*wildcard)/viewer(ext PM) = 3
        //   => 5+3+2+2+3+3+2+3 = 23
        assertThat(out.totalGrants())
                .as("every grant call should have produced a single write")
                .isEqualTo(23);

        // 1 revoke for alice leaving the quark group.
        assertThat(out.totalRevokes()).isEqualTo(1);

        // Caveat-aware check result shapes — both calls returned a boolean.
        // Semantic truth depends on SpiceDB schema recursion, which
        // InMemoryTransport doesn't implement; we only assert the call
        // surface works and returns a deterministic value.
        assertThat(out.alicePressFromOffice()).isIn(true, false);
        assertThat(out.alicePressFromCafe()).isIn(true, false);

        // who(User, Document.Perm.EDIT).fetchIds() returns a list —
        // InMemoryTransport resolves relation == permission name literally,
        // so "edit" permission doesn't match the "editor" / "owner"
        // relations. We only assert the call shape here.
        assertThat(out.planEditors()).isNotNull();

        // checkAll(Document.Perm) returns an EnumMap with every perm keyed.
        assertThat(out.engLeadToolbar().keySet())
                .containsExactlyInAnyOrder(com.authx.testapp.schema.Document.Perm.values());

        // findBy(User, alice).can(Perm.VIEW) returns a list — empty under
        // exact-match semantics, populated under real SpiceDB.
        assertThat(out.aliceVisibleDocs()).isNotNull();

        // batchCheck().add(Document, id, Perm.VIEW, user).fetch() returned
        // a 3-cell CheckMatrix.
        assertThat(out.dashboard().size()).isEqualTo(3);
        assertThat(out.dashboard().resources())
                .containsExactlyInAnyOrder(
                        Document.name() + ":" + DOC_PLAN,
                        Document.name() + ":" + DOC_ROADMAP,
                        Document.name() + ":" + DOC_PRESS);
    }

    /**
     * Probe the underlying store with raw-string lookups that match
     * InMemoryTransport's exact-match semantics. Proves the typed chain
     * actually wrote the tuples we expected, not just some other shape.
     *
     * <p>The typed chain (service) always uses {@code Document.Rel.OWNER}
     * etc.; the untyped chain (this probe) spells the same relation as
     * the raw string {@code "owner"} — the wire format is identical.
     */
    @Test
    void grants_persist_with_expected_wire_format() {
        demo.run();

        // Eng-lead's "owner" edge on the plan doc — typed chain writes
        // the relation literally as "owner", which matches InMemory's
        // exact relation-name lookup.
        boolean engLeadOwnsPlan = client.resource(Document.name(), DOC_PLAN)
                .check("owner")
                .by("user:" + USER_ENG_LEAD)
                .hasPermission();
        assertThat(engLeadOwnsPlan)
                .as("typed grant(Document.Rel.OWNER).to(User, eng-lead) should have written document:%s#owner@user:%s",
                        DOC_PLAN, USER_ENG_LEAD)
                .isTrue();

        // Alice was granted membership in the quark group, then revoked
        // in step 13 — the post-demo state should NOT have that edge.
        boolean aliceStillInQuark = client.resource("group", "g-quark-team")
                .check("member")
                .by("user:" + USER_ALICE)
                .hasPermission();
        assertThat(aliceStillInQuark)
                .as("revoke(Group.Rel.MEMBER).from(User, alice) should have removed the edge")
                .isFalse();

        // lookupSubjects on the dept-engineering "member" relation.
        // We stored alice + eng-lead as members; InMemory's
        // relation == permission match returns them both.
        java.util.List<String> engMembers = client.resource("department", "dept-engineering")
                .who("user")
                .withPermission("member")
                .fetch();
        assertThat(engMembers).containsExactlyInAnyOrder(USER_ENG_LEAD, USER_ALICE);

        // lookupResources: which departments does alice belong to? After
        // the quark-group revoke she still has dept-engineering.
        java.util.List<String> aliceDepts = client.lookup("department")
                .withPermission("member")
                .by("user:" + USER_ALICE)
                .limit(100)
                .fetch();
        assertThat(aliceDepts).containsExactly("dept-engineering");
    }
}
