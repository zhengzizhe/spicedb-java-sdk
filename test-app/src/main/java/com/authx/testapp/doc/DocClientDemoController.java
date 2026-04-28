package com.authx.testapp.doc;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.WriteCompletion;
import com.authx.sdk.WriteFlow;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.ExpandTree;
import com.authx.sdk.model.Tuple;
import com.authx.testapp.schema.DocNode;
import com.authx.testapp.schema.Org;
import com.authx.testapp.schema.User;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.authx.testapp.schema.DocNode.DocNode;
import static com.authx.testapp.schema.Org.Org;
import static com.authx.testapp.schema.User.User;

@RestController
@RequestMapping("/doc")
public class DocClientDemoController {

    private final AuthxClient client;

    public DocClientDemoController(AuthxClient client) {
        this.client = client;
    }

    @PostMapping("/nodes")
    public WriteResponse createNode(@RequestBody @NonNull CreateNodeRequest req) {
        WriteFlow flow = client.on(DocNode)
                .select(req.nodeId())
                .grant(DocNode.Rel.OWNER)
                .to(User, req.ownerUserId());

        if (req.parentId() != null && !req.parentId().isBlank()) {
            flow.grant(DocNode.Rel.PARENT).to(DocNode, req.parentId());
        }

        return write(req.nodeId(), flow.commit());
    }

    @PostMapping("/relationships/grant")
    public WriteResponse grant(@RequestBody RelationshipRequest req) {
        WriteCompletion completion = client.on(DocNode)
                .select(req.nodeId())
                .grant(req.relation())
                .to(req.subject())
                .commit();
        return write(req.nodeId(), completion);
    }

    @PostMapping("/relationships/revoke")
    public WriteResponse revoke(@RequestBody RelationshipRequest req) {
        WriteCompletion completion = client.on(DocNode)
                .select(req.nodeId())
                .revoke(req.relation())
                .from(req.subject())
                .commit();
        return write(req.nodeId(), completion);
    }

    @PostMapping("/org-members/grant")
    public WriteResponse grantOrgMember(@RequestBody OrgMemberRequest req) {
        WriteCompletion completion = client.on(Org)
                .select(req.orgId())
                .grant(Org.Rel.MEMBER)
                .to(User, req.userId())
                .commit();
        return write("org:" + req.orgId(), completion);
    }

    @PostMapping("/org-members/revoke")
    public WriteResponse revokeOrgMember(@RequestBody OrgMemberRequest req) {
        WriteCompletion completion = client.on(Org)
                .select(req.orgId())
                .revoke(Org.Rel.MEMBER)
                .from(User, req.userId())
                .commit();
        return write("org:" + req.orgId(), completion);
    }

    @PostMapping("/check")
    public CheckResponse check(@RequestBody CheckRequest req) {
        boolean allowed = client.on(DocNode)
                .select(req.nodeId())
                .check(req.permission())
                .withConsistency(Consistency.full())
                .by(req.subject());
        return new CheckResponse(req.nodeId(), req.permission(), req.subject(), allowed);
    }

    @GetMapping("/nodes/{nodeId}/relations")
    public RelationsResponse relations(@PathVariable String nodeId) {
        List<TupleView> tuples = client.on(DocNode)
                .select(nodeId)
                .relations()
                .withConsistency(Consistency.full())
                .fetch()
                .stream()
                .map(TupleView::from)
                .toList();
        return new RelationsResponse(nodeId, tuples);
    }

    @GetMapping("/nodes/{nodeId}/expand/{permission}")
    public ExpandTree expand(@PathVariable String nodeId, @PathVariable DocNode.Perm permission) {
        return client.on(DocNode)
                .select(nodeId)
                .expand(permission);
    }

    private WriteResponse write(String resource, WriteCompletion completion) {
        return new WriteResponse(resource, completion.zedToken(), completion.count());
    }

    public record CreateNodeRequest(String nodeId, String ownerUserId, String parentId) {}

    public record RelationshipRequest(String nodeId, DocNode.Rel relation, String subject) {}

    public record OrgMemberRequest(String orgId, String userId) {}

    public record CheckRequest(String nodeId, DocNode.Perm permission, String subject) {}

    public record WriteResponse(String resource, String zedToken, int relationshipCount) {}

    public record CheckResponse(String nodeId, DocNode.Perm permission, String subject, boolean allowed) {}

    public record TupleView(String resource, String relation, String subject) {
        static TupleView from(Tuple tuple) {
            return new TupleView(tuple.resource(), tuple.relation(), tuple.subject());
        }
    }

    public record RelationsResponse(String nodeId, List<TupleView> tuples) {}
}
