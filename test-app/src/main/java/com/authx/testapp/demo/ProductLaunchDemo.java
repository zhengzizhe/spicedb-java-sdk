package com.authx.testapp.demo;

import static com.authx.testapp.schema.Schema.Department;
import static com.authx.testapp.schema.Schema.Document;
import static com.authx.testapp.schema.Schema.Folder;
import static com.authx.testapp.schema.Schema.Group;
import static com.authx.testapp.schema.Schema.Organization;
import static com.authx.testapp.schema.Schema.Space;
import static com.authx.testapp.schema.Schema.User;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.CheckMatrix;
import com.authx.sdk.model.SubjectRef;
import com.authx.testapp.schema.IpAllowlist;

import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end walk-through of the post-2026-04-22 typed descriptor API.
 *
 * <p>The story: Acme Corp launches product "Quark". The demo provisions an
 * org, a department tree, a group, a space + folder hierarchy, three
 * documents, and then grants / revokes / checks / looks up through every
 * entry point of the fluent chain.
 *
 * <p>Idioms being demonstrated:
 * <ul>
 *   <li>{@code import static Schema.*} — business code spells
 *       {@code Document} / {@code User} / {@code Folder} as values, not
 *       {@code Document.TYPE}.</li>
 *   <li>{@code client.on(Document).select(id).grant(Document.Rel.EDITOR)
 *       .to(User, userId)} — typed subject with bare id.</li>
 *   <li>{@code .to(Group, id, Group.Rel.MEMBER)} / {@code .to(Department,
 *       id, Department.Perm.ALL_MEMBERS)} — typed sub-relations (and
 *       sub-permissions that act as subject sets).</li>
 *   <li>{@code .toWildcard(User)} — {@code user:*} grants.</li>
 *   <li>{@code .withCaveat(IpAllowlist.ref(...))} — caveat-gated grants.</li>
 *   <li>{@code .checkAll(Document.Perm).by(User, id)} — whole-enum matrix.</li>
 *   <li>{@code .who(User, Perm.EDIT).fetchIds()} — lookupSubjects.</li>
 *   <li>{@code .findBy(User, id).can(Perm.VIEW)} — lookupResources.</li>
 *   <li>{@code client.batchCheck().add(Document, id, Perm.VIEW, user).fetch()}
 *       — cross-resource batched check.</li>
 * </ul>
 *
 * <p><b>Implementation note:</b> this demo is wired against the SDK's
 * in-memory transport ({@link AuthxClient#inMemory()}), which stores
 * relationships in a map and resolves checks by exact
 * {@code (resource, relation, subject)} match — it does <em>not</em>
 * run SpiceDB's schema engine. That means inheritance
 * ({@code document.view = editor + folder.view}) is not computed here;
 * only direct grants resolve as allowed. The demo is therefore a
 * <em>compile-and-wire</em> check of the new API surface, not a
 * permission-semantics simulator. Against a real SpiceDB (via
 * {@code AuthxClient.builder().connection(...)}) the same code returns
 * the full inherited picture.
 */
@Service
public class ProductLaunchDemo {

    // ── Cast of characters ────────────────────────────────────────────
    public static final String ORG = "acme";
    public static final String DEPT_ENG = "dept-engineering";
    public static final String DEPT_MKT = "dept-marketing";
    public static final String GRP_QUARK = "g-quark-team";
    public static final String SPACE_MAIN = "spc-acme-main";
    public static final String FLD_ROOT = "fld-root";
    public static final String FLD_QUARK = "fld-quark";
    public static final String DOC_PLAN = "doc-quark-plan";
    public static final String DOC_ROADMAP = "doc-quark-roadmap";
    public static final String DOC_PRESS = "doc-quark-press";

    public static final String USER_CEO = "u-ceo";
    public static final String USER_ENG_LEAD = "u-eng-lead";
    public static final String USER_MKT_LEAD = "u-mkt-lead";
    public static final String USER_ALICE = "u-alice";
    public static final String USER_BOB = "u-bob";
    public static final String USER_EXTERNAL_PM = "u-external-pm";

    public static final String OFFICE_CIDR = "203.0.113.";

    private final AuthxClient client;

    public ProductLaunchDemo(AuthxClient client) {
        this.client = client;
    }

    /**
     * Run the full story end-to-end and return a structured outcome for
     * the test harness / CLI to inspect.
     */
    public DemoOutcome run() {
        int writes = 0;

        // ── 1. Organisation bootstrap ────────────────────────────────
        //   CEO is admin; four employees are regular members.
        writes += client.on(Organization).select(ORG)
                .grant(Organization.Rel.ADMIN)
                .to(User, USER_CEO)
                .result().count();

        writes += client.on(Organization).select(ORG)
                .grant(Organization.Rel.MEMBER)
                .to(User, List.of(USER_ENG_LEAD, USER_MKT_LEAD, USER_ALICE, USER_BOB))
                .result().count();

        // ── 2. Departments + membership ──────────────────────────────
        //   Engineering = eng-lead + alice;  Marketing = mkt-lead.
        writes += client.on(Department).select(DEPT_ENG)
                .grant(Department.Rel.MEMBER)
                .to(User, List.of(USER_ENG_LEAD, USER_ALICE))
                .result().count();

        writes += client.on(Department).select(DEPT_MKT)
                .grant(Department.Rel.MEMBER)
                .to(User, USER_MKT_LEAD)
                .result().count();

        // ── 3. Quark cross-functional group ──────────────────────────
        //   Two explicit members — eng-lead + alice.
        writes += client.on(Group).select(GRP_QUARK)
                .grant(Group.Rel.MEMBER)
                .to(User, List.of(USER_ENG_LEAD, USER_ALICE))
                .result().count();

        // ── 4. Space — owned by the org ──────────────────────────────
        //   "space.org = organization" subject, so org admins inherit
        //   space admin. CEO gets it for free via step 1.
        writes += client.on(Space).select(SPACE_MAIN)
                .grant(Space.Rel.ORG)
                .to(Organization, ORG)
                .result().count();

        //   Alice is an explicit space member (writes an extra direct
        //   edge so she shows up in lookupSubjects).
        writes += client.on(Space).select(SPACE_MAIN)
                .grant(Space.Rel.MEMBER)
                .to(User, USER_ALICE)
                .result().count();

        // ── 5. Folder hierarchy inside the space ─────────────────────
        //   root folder -> quark folder.
        writes += client.on(Folder).select(FLD_ROOT)
                .grant(Folder.Rel.SPACE)
                .to(Space, SPACE_MAIN)
                .result().count();

        writes += client.on(Folder).select(FLD_QUARK)
                .grant(Folder.Rel.PARENT)
                .to(Folder, FLD_ROOT)
                .result().count();

        //   Eng-lead owns the quark folder.
        writes += client.on(Folder).select(FLD_QUARK)
                .grant(Folder.Rel.OWNER)
                .to(User, USER_ENG_LEAD)
                .result().count();

        // ── 6. Documents in the quark folder ─────────────────────────
        //   doc-quark-plan:  live inside fld-quark;
        //                    editor = the quark team (group members);
        //                    owner  = eng-lead.
        writes += client.on(Document).select(DOC_PLAN)
                .grant(Document.Rel.FOLDER)
                .to(Folder, FLD_QUARK)
                .result().count();

        writes += client.on(Document).select(DOC_PLAN)
                .grant(Document.Rel.EDITOR)
                .to(Group, GRP_QUARK, Group.Rel.MEMBER)        // typed sub-relation
                .result().count();

        writes += client.on(Document).select(DOC_PLAN)
                .grant(Document.Rel.OWNER)
                .to(User, USER_ENG_LEAD)
                .result().count();

        //   doc-quark-roadmap: viewer = engineering dept#all_members
        //                      (permission-as-subject-set).
        writes += client.on(Document).select(DOC_ROADMAP)
                .grant(Document.Rel.FOLDER)
                .to(Folder, FLD_QUARK)
                .result().count();

        writes += client.on(Document).select(DOC_ROADMAP)
                .grant(Document.Rel.VIEWER)
                .to(Department, DEPT_ENG, Department.Perm.ALL_MEMBERS)  // typed sub-permission
                .result().count();

        //   doc-quark-press: public via user:* wildcard, gated on a
        //   caveat that only allows access from the office CIDR.
        writes += client.on(Document).select(DOC_PRESS)
                .grant(Document.Rel.FOLDER)
                .to(Folder, FLD_QUARK)
                .result().count();

        writes += client.on(Document).select(DOC_PRESS)
                .grant(Document.Rel.VIEWER)
                .withCaveat(IpAllowlist.ref(IpAllowlist.CIDRS, List.of(OFFICE_CIDR)))
                .toWildcard(User)
                .result().count();

        // ── 7. External PM — scoped to the press doc only ────────────
        //   Granted explicit viewer on just doc-quark-press (no caveat
        //   — internal PM may view from any IP).
        writes += client.on(Document).select(DOC_PRESS)
                .grant(Document.Rel.VIEWER)
                .to(User, USER_EXTERNAL_PM)
                .result().count();

        // ── 8. Caveat-aware permission check ─────────────────────────
        //   Alice hitting the press doc from the office network — the
        //   caveat context is supplied at check time.
        boolean alicePressFromOffice = client.on(Document).select(DOC_PRESS)
                .check(Document.Perm.VIEW)
                .withContext(IpAllowlist.CLIENT_IP, OFFICE_CIDR + "42")
                .by(User, USER_ALICE);

        //   Same check from a coffee-shop IP — against a real SpiceDB
        //   this would drop to CONDITIONAL_PERMISSION / denied.
        boolean alicePressFromCafe = client.on(Document).select(DOC_PRESS)
                .check(Document.Perm.VIEW)
                .withContext(IpAllowlist.CLIENT_IP, "198.51.100.7")
                .by(User, USER_ALICE);

        // ── 9. Who-can-edit the plan?  (lookupSubjects) ──────────────
        List<String> planEditors = client.on(Document).select(DOC_PLAN)
                .who(User, Document.Perm.EDIT)
                .fetchIds();

        // ── 10. Whole-enum permission matrix for the eng-lead ────────
        //   One round trip computes VIEW/EDIT/COMMENT/SHARE/DELETE/MANAGE
        //   against the plan doc.
        EnumMap<com.authx.testapp.schema.Document.Perm, Boolean> toolbar =
                client.on(Document).select(DOC_PLAN)
                        .checkAll(Document.Perm)
                        .by(User, USER_ENG_LEAD);

        // ── 11. Reverse lookup — what can alice see? ─────────────────
        List<String> aliceVisible = client.on(Document)
                .findBy(User, USER_ALICE)
                .limit(100)
                .can(Document.Perm.VIEW);

        // ── 12. Cross-resource batch check for a dashboard widget ────
        //   "Can eng-lead view plan AND roadmap AND press in one RPC?"
        SubjectRef engLeadRef = SubjectRef.of(User.name(), USER_ENG_LEAD);
        CheckMatrix dashboard = client.batchCheck()
                .add(Document, DOC_PLAN,    Document.Perm.VIEW, engLeadRef)
                .add(Document, DOC_ROADMAP, Document.Perm.VIEW, engLeadRef)
                .add(Document, DOC_PRESS,   Document.Perm.VIEW, engLeadRef)
                .fetch();

        // ── 13. Offboarding — revoke alice from the quark group ──────
        int revoked = client.on(Group).select(GRP_QUARK)
                .revoke(Group.Rel.MEMBER)
                .from(User, USER_ALICE)
                .result().count();

        return new DemoOutcome(
                writes,
                revoked,
                alicePressFromOffice,
                alicePressFromCafe,
                planEditors,
                Map.copyOf(toolbar),
                aliceVisible,
                dashboard);
    }

    /** Everything the demo surfaces to the caller for inspection. */
    public record DemoOutcome(
            int totalGrants,
            int totalRevokes,
            boolean alicePressFromOffice,
            boolean alicePressFromCafe,
            List<String> planEditors,
            Map<com.authx.testapp.schema.Document.Perm, Boolean> engLeadToolbar,
            List<String> aliceVisibleDocs,
            CheckMatrix dashboard) {}
}
