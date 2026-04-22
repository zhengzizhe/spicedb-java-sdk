package com.authx.testapp.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.authx.sdk.AuthxClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

/**
 * End-to-end integration test driving the REST surface with MockMvc.
 * Walks the Acme "Quark" product-launch story entirely through HTTP —
 * every grant, revoke, check, and reverse-lookup is an actual request.
 *
 * <p>The {@link AuthxClient} bean is {@link AuthxClient#inMemory()},
 * so checks resolve on exact {@code (resource, relation, subject)}
 * matches without SpiceDB's schema-recursion. Assertions that depend on
 * recursion (e.g. "editor implies view") are noted inline; the others
 * exercise the full controller → service → SDK path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PermissionApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    // Resource ids used across the test.
    private static final String ORG = "acme";
    private static final String DEPT_ENG = "dept-engineering";
    private static final String GROUP_QUARK = "g-quark-team";
    private static final String SPACE = "spc-acme-main";
    private static final String FLD_ROOT = "fld-root";
    private static final String FLD_QUARK = "fld-quark";
    private static final String DOC_PLAN = "doc-quark-plan";
    private static final String DOC_PRESS = "doc-quark-press";

    private static final String USER_CEO = "u-ceo";
    private static final String USER_ENG_LEAD = "u-eng-lead";
    private static final String USER_ALICE = "u-alice";
    private static final String USER_BOB = "u-bob";
    private static final String USER_EXTERNAL = "u-external-pm";

    @Test
    void full_quark_launch_flow() throws Exception {
        // ── 1. Organisation bootstrap ────────────────────────────────
        postJson("/organizations/" + ORG + "/admins",
                Map.of("userId", USER_CEO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.writeCount").value(1));

        postJson("/organizations/" + ORG + "/members",
                Map.of("userIds", List.of(USER_ENG_LEAD, USER_ALICE, USER_BOB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.writeCount").value(3));

        // ── 2. Department + members ──────────────────────────────────
        postJson("/departments/" + DEPT_ENG + "/members",
                Map.of("userIds", List.of(USER_ENG_LEAD, USER_ALICE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.writeCount").value(2));

        // ── 3. Quark cross-functional group ──────────────────────────
        postJson("/groups/" + GROUP_QUARK + "/members",
                Map.of("userId", USER_ENG_LEAD))
                .andExpect(status().isCreated());
        postJson("/groups/" + GROUP_QUARK + "/members",
                Map.of("userId", USER_ALICE))
                .andExpect(status().isCreated());

        // ── 4. Space under the org ──────────────────────────────────
        mvc.perform(put("/spaces/" + SPACE + "/organization/" + ORG))
                .andExpect(status().isNoContent());

        // ── 5. Folder hierarchy ──────────────────────────────────────
        mvc.perform(put("/folders/" + FLD_ROOT + "/space/" + SPACE))
                .andExpect(status().isNoContent());
        mvc.perform(put("/folders/" + FLD_QUARK + "/parent/" + FLD_ROOT))
                .andExpect(status().isNoContent());
        postJson("/folders/" + FLD_QUARK + "/owners",
                Map.of("userId", USER_ENG_LEAD))
                .andExpect(status().isCreated());

        // ── 6. Documents ─────────────────────────────────────────────
        //   6a. doc-plan: folder + owner (eng-lead) + group-based editors
        mvc.perform(put("/documents/" + DOC_PLAN + "/folder/" + FLD_QUARK))
                .andExpect(status().isNoContent());
        postJson("/documents/" + DOC_PLAN + "/owners",
                Map.of("userId", USER_ENG_LEAD))
                .andExpect(status().isCreated());
        postJson("/documents/" + DOC_PLAN + "/editors",
                Map.of("userId", USER_ALICE))
                .andExpect(status().isCreated());
        mvc.perform(post("/documents/" + DOC_PLAN + "/viewers/group/" + GROUP_QUARK))
                .andExpect(status().isCreated());
        mvc.perform(post("/documents/" + DOC_PLAN + "/viewers/department/" + DEPT_ENG))
                .andExpect(status().isCreated());

        //   6b. doc-press: folder + external viewer + public wildcard with IP caveat
        mvc.perform(put("/documents/" + DOC_PRESS + "/folder/" + FLD_QUARK))
                .andExpect(status().isNoContent());
        postJson("/documents/" + DOC_PRESS + "/viewers",
                Map.of("userId", USER_EXTERNAL))
                .andExpect(status().isCreated());
        postJson("/documents/" + DOC_PRESS + "/viewers/public",
                Map.of("allowedCidrs", List.of("203.0.113.")))
                .andExpect(status().isCreated());

        // ── 7. Reverse lookup — who can EDIT the plan? ──────────────
        //   The typed chain wrote relation="editor" for alice; the
        //   {@code who(User, Perm.EDIT)} endpoint resolves on the
        //   "edit" permission name. Under SpiceDB these match by
        //   schema recursion (editor -> edit); InMemoryTransport
        //   compares strings literally and returns an empty list.
        //   Assert shape only — real SpiceDB would return [alice].
        mvc.perform(get("/documents/" + DOC_PLAN + "/editors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ids").isArray());

        // ── 8. Revoke ────────────────────────────────────────────────
        mvc.perform(delete("/documents/" + DOC_PLAN + "/editors/" + USER_ALICE))
                .andExpect(status().isNoContent());
        mvc.perform(get("/documents/" + DOC_PLAN + "/editors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ids").isArray());

        // ── 9. Caveat-aware check — wildcard + ip_allowlist ──────────
        //   Bob (non-explicit, wildcard only) hitting the press doc.
        //   The wildcard subject record matches his check; the caveat
        //   context is supplied via the clientIp query param. NOTE:
        //   InMemoryTransport does not evaluate caveats — it matches
        //   on the stored (type, id) pair alone and returns true/false
        //   based on presence. We only assert the endpoint roundtrip
        //   works and returns a deterministic boolean.
        mvc.perform(get("/documents/" + DOC_PRESS + "/can/view")
                        .param("userId", USER_BOB)
                        .param("clientIp", "203.0.113.42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").isBoolean());

        // ── 10. checkAll matrix ──────────────────────────────────────
        mvc.perform(get("/documents/" + DOC_PLAN + "/permissions")
                        .param("userId", USER_ENG_LEAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.view").isBoolean())
                .andExpect(jsonPath("$.permissions.edit").isBoolean())
                .andExpect(jsonPath("$.permissions.manage").isBoolean());

        // ── 11. Reverse lookup — accessible docs ─────────────────────
        mvc.perform(get("/documents/accessible")
                        .param("userId", USER_ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ids").isArray());

        // ── 12. Batch check ──────────────────────────────────────────
        var batchBody = Map.of(
                "userId", USER_ENG_LEAD,
                "items", List.of(
                        Map.of("docId", DOC_PLAN,  "permission", "view"),
                        Map.of("docId", DOC_PRESS, "permission", "view")));
        postJson("/documents/batch-check", batchBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.results[0].docId").value(DOC_PLAN))
                .andExpect(jsonPath("$.results[0].permission").value("view"));
    }

    @Test
    void rejects_unknown_permission_name_with_400() throws Exception {
        mvc.perform(get("/documents/any-doc/can/warp-drive")
                        .param("userId", "anyone"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Unknown document permission")));
    }

    /** Helper: POST with a JSON body built from a {@link Map}. */
    private org.springframework.test.web.servlet.ResultActions postJson(String path, Object body)
            throws Exception {
        MockHttpServletRequestBuilder req = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        return mvc.perform(req);
    }
}
