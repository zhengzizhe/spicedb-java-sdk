package com.authx.testapp.service;

import com.authx.sdk.AuthxClient;
import com.authx.testapp.schema.Document;
import com.authx.testapp.schema.IpAllowlist;
import com.authx.testapp.schema.User;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 条件分享示范：IP 白名单受限的链接分享。
 *
 * <p>展示 typed caveat 的端到端用法：grant 时通过 {@link IpAllowlist#ref}
 * 绑定静态参数 {@code cidrs}，check 时通过 {@link IpAllowlist#CLIENT_IP}
 * 常量传入动态参数 {@code client_ip}。CEL 表达式
 * {@code cidrs.exists(c, client_ip.startsWith(c))} 由 SpiceDB 服务端评估，
 * SDK / 业务代码不做任何条件逻辑 —— 所有字符串字面量来自 codegen，
 * 没有"caveat name"或"parameter name"的手写拼接。
 *
 * <p>典型场景：一篇内部文档，用"谁都可以打开"的 {@code link_viewer}
 * 关系 + {@code ip_allowlist} 受限，只有从公司网络访问的请求才会通过。
 *
 * <pre>
 * conditional.shareOnCorpNetwork("doc-1", List.of("10.0.0.0/8", "172.16.0.0/12"));
 * boolean canOpenFromCafe    = conditional.canOpenFrom("doc-1", "alice", "8.8.8.8");       // false
 * boolean canOpenFromOffice  = conditional.canOpenFrom("doc-1", "alice", "10.5.42.7");     // true
 * </pre>
 */
@Service
public class ConditionalShareService {

    private final AuthxClient client;

    public ConditionalShareService(AuthxClient client) {
        this.client = client;
    }

    /**
     * 把文档发布成"任何人可看，但只在这些 CIDR 里"。
     *
     * <p>{@code IpAllowlist.ref(IpAllowlist.CIDRS, allowedCidrs)} 构造一个
     * {@code CaveatRef("ip_allowlist", {cidrs: [...]})}，SDK 在写关系时
     * 把它塞进 {@code RelationshipUpdate.caveat} 字段。
     */
    public void shareOnCorpNetwork(String docId, List<String> allowedCidrs) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(Document.Rel.LINK_VIEWER)
                .onlyIf(IpAllowlist.ref(IpAllowlist.CIDRS, allowedCidrs))
                .toWildcard(User.TYPE);
    }

    /**
     * 撤销 IP 受限的链接分享。wildcard subject 由
     * {@link com.authx.sdk.TypedRevokeAction#fromWildcard} 重载构造，
     * 不需要手写 {@code "user:*"}。
     */
    public void stopSharing(String docId) {
        client.on(Document.TYPE)
                .select(docId)
                .revoke(Document.Rel.LINK_VIEWER)
                .fromWildcard(User.TYPE);
    }

    /**
     * 用户在观察到的 client IP 前提下能否看这个文档。
     *
     * <p>{@code given(IpAllowlist.CLIENT_IP, clientIp)} 构造 check 时的
     * caveat context；{@code by(User.TYPE, userId)} 构造 subject。SpiceDB
     * 对 link_viewer 关系带的 {@code ip_allowlist} caveat 执行 CEL，
     * 返回最终的 true / false。如果文档没有走 link_viewer 路径（例如
     * 直接被 editor 分享），caveat 不参与判定，context 被忽略。
     */
    public boolean canOpenFrom(String docId, String userId, String clientIp) {
        return client.on(Document.TYPE)
                .select(docId)
                .check(Document.Perm.VIEW)
                .given(IpAllowlist.CLIENT_IP, clientIp)
                .by(User.TYPE, userId);
    }
}
