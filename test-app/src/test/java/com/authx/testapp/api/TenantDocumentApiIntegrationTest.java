package com.authx.testapp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * End-to-end walkthrough of the {@code tenant_document} REST surface —
 * verifies the multi-tenant isolation API shape.
 *
 * <p>The {@link AuthxClient} bean is {@link AuthxClient#inMemory()} so
 * tuples are stored but SpiceDB's permission recursion (in particular
 * the {@code & org->access} intersection) is NOT evaluated. We assert
 * HTTP contract + tuple layout; the intersection's effect on
 * {@code /can/...} lives behind a real SpiceDB deployment. Comments
 * below mark which assertions would flip under real recursion.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantDocumentApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static final String ORG_A = "org-acme";
    private static final String ORG_B = "org-beta";
    private static final String DOC_A1 = "doc-a-1";
    private static final String DOC_B1 = "doc-b-1";
    private static final String ALICE = "u-alice";
    private static final String BOB = "u-bob";
    private static final String CAROL = "u-carol";
    private static final String CFO = "u-cfo";

    @Test
    void cross_org_isolation_flow() throws Exception {
        // ── 1. 在 org-acme 创建 doc-a-1，alice 为 owner ──────────────
        mvc.perform(put("/tenant-documents/" + DOC_A1)
                        .param("orgId", ORG_A)
                        .param("ownerId", ALICE))
                .andExpect(status().isCreated());

        // ── 2. 在 org-beta 创建 doc-b-1，bob 为 owner ──────────────
        mvc.perform(put("/tenant-documents/" + DOC_B1)
                        .param("orgId", ORG_B)
                        .param("ownerId", BOB))
                .andExpect(status().isCreated());

        // ── 3. 绑定的 org 能查出来（审计/诊断用） ──────────────
        mvc.perform(get("/tenant-documents/" + DOC_A1 + "/org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ids[0]").value("organization:" + ORG_A));

        // ── 4. Carol 作为 org-acme 的成员（必须先加 org 再授权） ────────
        mvc.perform(post("/tenant-documents/orgs/" + ORG_A + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userId", CAROL))))
                .andExpect(status().isCreated());

        // ── 5. Carol 被授予 doc-a-1 的 viewer ──────────────────────
        mvc.perform(post("/tenant-documents/" + DOC_A1 + "/viewers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userId", CAROL))))
                .andExpect(status().isCreated());

        // ── 6. Bob 被授予 doc-a-1 的 viewer，但他只在 org-beta ──────
        //      HTTP 层会接受 —— SDK 不做业务校验
        //      真实 SpiceDB：check 会返 false（bob 不是 org-acme 成员）
        mvc.perform(post("/tenant-documents/" + DOC_A1 + "/viewers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userId", BOB))))
                .andExpect(status().isCreated());

        // ── 7. 改 carol 角色：viewer → editor（原子 revoke + grant） ─
        mvc.perform(put("/tenant-documents/" + DOC_A1 + "/role/" + CAROL)
                        .param("from", "VIEWER")
                        .param("to", "EDITOR"))
                .andExpect(status().isNoContent());

        // ── 8. 转让所有权：alice → cfo ─────────────────────────────
        mvc.perform(post("/tenant-documents/orgs/" + ORG_A + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userId", CFO))))
                .andExpect(status().isCreated());

        mvc.perform(put("/tenant-documents/" + DOC_A1 + "/owner")
                        .param("from", ALICE)
                        .param("to", CFO))
                .andExpect(status().isNoContent());

        // ── 9. 批量 viewer ────────────────────────────────────────
        mvc.perform(post("/tenant-documents/" + DOC_A1 + "/viewers/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("userIds", List.of("u-x", "u-y", "u-z")))))
                .andExpect(status().isCreated());

        // ── 10. 黑名单 ────────────────────────────────────────────
        mvc.perform(post("/tenant-documents/" + DOC_A1 + "/ban/u-x"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/tenant-documents/" + DOC_A1 + "/ban/u-x"))
                .andExpect(status().isNoContent());

        // ── 11. 公开（org 内）────────────────────────────────────────
        mvc.perform(post("/tenant-documents/" + DOC_A1 + "/share/public"))
                .andExpect(status().isCreated());

        // ── 12. 提升 admin ────────────────────────────────────────
        mvc.perform(post("/tenant-documents/orgs/" + ORG_A + "/admins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userId", CFO))))
                .andExpect(status().isCreated());

        // ── 13. 踢出 org：所有 tenant_document 权限瞬间失效
        //       真实 SpiceDB：之后 carol 的 can(EDIT, doc-a-1) = false
        //       （intersection 失败，tuple 仍存在）
        mvc.perform(delete("/tenant-documents/orgs/" + ORG_A + "/members/" + CAROL))
                .andExpect(status().isNoContent());

        // ── 14. HTTP check 端点（InMemoryTransport 不走 recursion，
        //       这里只验证契约；真实环境会根据 schema 返 true/false）──
        mvc.perform(get("/tenant-documents/" + DOC_A1 + "/can/VIEW")
                        .param("userId", CFO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docId").value(DOC_A1))
                .andExpect(jsonPath("$.permission").value("view"))
                .andExpect(jsonPath("$.userId").value(CFO));

        // ── 15. 清理单个 user 对 doc 的所有访问 ────────────────────
        mvc.perform(delete("/tenant-documents/" + DOC_A1 + "/access/u-y"))
                .andExpect(status().isNoContent());
    }

    @Test
    void create_requires_org_binding() throws Exception {
        // 没传 orgId 参数 → 400
        mvc.perform(put("/tenant-documents/doc-orphan")
                        .param("ownerId", ALICE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void role_change_uses_typed_enum_binding() throws Exception {
        mvc.perform(put("/tenant-documents/doc-roles")
                        .param("orgId", ORG_A)
                        .param("ownerId", ALICE))
                .andExpect(status().isCreated());

        // 非法 enum 值 → 400（Spring 自动拒绝）
        mvc.perform(put("/tenant-documents/doc-roles/role/" + ALICE)
                        .param("from", "NOT_A_REL")
                        .param("to", "VIEWER"))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder postJson(String uri, Object body) throws Exception {
        return post(uri).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }
}
