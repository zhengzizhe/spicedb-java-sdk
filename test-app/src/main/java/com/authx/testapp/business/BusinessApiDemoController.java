package com.authx.testapp.business;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.WriteCompletion;
import com.authx.sdk.model.CheckMatrix;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.ExpandTree;
import com.authx.sdk.model.Tuple;
import com.authx.testapp.schema.Org;
import com.authx.testapp.schema.Project;
import com.authx.testapp.schema.Task;
import com.authx.testapp.schema.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.authx.testapp.schema.Org.Org;
import static com.authx.testapp.schema.Project.Project;
import static com.authx.testapp.schema.Task.Task;
import static com.authx.testapp.schema.User.User;

@RestController
@RequestMapping("/business-api")
public class BusinessApiDemoController {

    private static final Logger log = LoggerFactory.getLogger(BusinessApiDemoController.class);

    private final AuthxClient client;

    public BusinessApiDemoController(AuthxClient client) {
        this.client = client;
    }

    @PostMapping("/grant")
    public void grant(@RequestBody ProjectUser req) {
        client.on(Project)
                .select(req.projectId())
                .grant(Project.Rel.MEMBER)
                .to(User, req.userId())
                .commit();
    }

    @PostMapping("/grant-subject-set")
    public void grantSubjectSet(@RequestBody ProjectOrg req) {
        client.on(Project)
                .select(req.projectId())
                .grant(Project.Rel.MEMBER)
                .to(Org, req.orgId(), Org.Rel.MEMBER)
                .commit();
    }

    @PostMapping("/grant-wildcard")
    public void grantWildcard(@RequestBody ProjectOnly req) {
        client.on(Project)
                .select(req.projectId())
                .grant(Project.Rel.MEMBER)
                .toWildcard(User)
                .commit();
    }

    @PostMapping("/write-flow")
    public void writeFlow(@RequestBody WriteFlowReq req) {
        client.on(Project)
                .select(req.projectId())
                .revoke(Project.Rel.MEMBER)
                .from(User, req.oldUserId())
                .grant(Project.Rel.MANAGER)
                .to(User, req.newUserId())
                .commit();
    }

    @PostMapping("/listener")
    public void listener(@RequestBody ProjectUser req) {
        WriteCompletion completion = client.on(Project)
                .select(req.projectId())
                .grant(Project.Rel.MEMBER)
                .to(User, req.userId())
                .commit();

        completion.listener(done -> log.info("project member granted, count={}", done.count()));
    }

    @PostMapping("/check")
    public boolean check(@RequestBody ProjectUser req) {
        return client.on(Project)
                .select(req.projectId())
                .check(Project.Perm.VIEW)
                .by(User, req.userId());
    }

    @PostMapping("/check-detailed")
    public CheckResult checkDetailed(@RequestBody ProjectUser req) {
        return client.on(Project)
                .select(req.projectId())
                .check(Project.Perm.VIEW)
                .detailedBy(User, req.userId());
    }

    @PostMapping("/check-all")
    public EnumMap<Project.Perm, Boolean> checkAll(@RequestBody ProjectUser req) {
        return client.on(Project)
                .select(req.projectId())
                .checkAll()
                .by(User, req.userId());
    }

    @PostMapping("/check-matrix")
    public List<CheckMatrix.Cell> checkMatrix(@RequestBody MatrixReq req) {
        return client.on(Project)
                .select(req.projectIds())
                .check(Project.Perm.VIEW, Project.Perm.MANAGE)
                .byAll(User, req.userIds())
                .cells();
    }

    @PostMapping("/batch-write")
    public void batchWrite(@RequestBody BatchWriteReq req) {
        client.batch()
                .on(Project, req.projectId())
                    .grant(Project.Rel.MEMBER).to(User, req.userId())
                .onAll(Task, req.taskIds())
                    .grant(Task.Rel.REVIEWER).to(User, req.userId())
                .commit();
    }

    @PostMapping("/batch-check")
    public List<CheckMatrix.Cell> batchCheck(@RequestBody BatchCheckReq req) {
        return client.batchCheck()
                .add(Project, req.projectId(), Project.Perm.VIEW, User, req.userId())
                .addAll(Task, req.taskIds(), Task.Perm.EDIT, User, req.userId())
                .fetch()
                .cells();
    }

    @PostMapping("/lookup-resources")
    public List<String> lookupResources(@RequestBody UserOnly req) {
        return client.on(Project)
                .lookupResources(User, req.userId())
                .limit(100)
                .can(Project.Perm.VIEW);
    }

    @PostMapping("/lookup-resources-by-permissions")
    public Map<Project.Perm, List<String>> lookupResourcesByPermissions(@RequestBody UserOnly req) {
        return client.on(Project)
                .lookupResources(User, req.userId())
                .can(Project.Perm.VIEW, Project.Perm.MANAGE);
    }

    @PostMapping("/lookup-resources-any")
    public List<String> lookupResourcesAny(@RequestBody UserOnly req) {
        return client.on(Project)
                .lookupResources(User, req.userId())
                .canAny(Project.Perm.VIEW, Project.Perm.MANAGE);
    }

    @PostMapping("/lookup-resources-all")
    public List<String> lookupResourcesAll(@RequestBody UserOnly req) {
        return client.on(Project)
                .lookupResources(User, req.userId())
                .canAll(Project.Perm.VIEW, Project.Perm.MANAGE);
    }

    @PostMapping("/lookup-resources-by-subjects")
    public Map<String, List<String>> lookupResourcesBySubjects(@RequestBody UsersOnly req) {
        return client.on(Project)
                .lookupResources(User, req.userIds())
                .can(Project.Perm.VIEW);
    }

    @PostMapping("/lookup-subjects")
    public List<String> lookupSubjects(@RequestBody ProjectOnly req) {
        return client.on(Project)
                .select(req.projectId())
                .lookupSubjects(User, Project.Perm.VIEW)
                .limit(100)
                .fetchIds();
    }

    @PostMapping("/lookup-subjects-exists")
    public boolean lookupSubjectsExists(@RequestBody ProjectOnly req) {
        return client.on(Project)
                .select(req.projectId())
                .lookupSubjects(User, Project.Perm.VIEW)
                .exists();
    }

    @PostMapping("/relations")
    public List<Tuple> relations(@RequestBody ProjectOnly req) {
        return client.on(Project)
                .select(req.projectId())
                .relations()
                .fetch();
    }

    @PostMapping("/relations-grouped")
    public Map<String, List<String>> relationsGrouped(@RequestBody ProjectOnly req) {
        return client.on(Project)
                .select(req.projectId())
                .relations(Project.Rel.OWNER, Project.Rel.MANAGER, Project.Rel.MEMBER)
                .groupByRelation();
    }

    @PostMapping("/expand")
    public ExpandTree expand(@RequestBody ProjectOnly req) {
        return client.on(Project)
                .select(req.projectId())
                .expand(Project.Perm.VIEW);
    }

    @PostMapping("/consistency")
    public boolean consistency(@RequestBody ProjectUser req) {


        return client.on(Project)
                .select(req.projectId())
                .check(Project.Perm.VIEW)
                .withConsistency(Consistency.full())
                .by(User, req.userId());
    }

    @PostMapping("/dynamic")
    public boolean dynamic(@RequestBody ProjectUser req) {
        return client.on("project")
                .select(req.projectId())
                .check("view")
                .by("user:" + req.userId());
    }

    @GetMapping("/health")
    public Object health() {
        return client.health();
    }

    public record ProjectOnly(String projectId) {}

    public record UserOnly(String userId) {}

    public record UsersOnly(List<String> userIds) {}

    public record ProjectUser(String projectId, String userId) {}

    public record ProjectOrg(String projectId, String orgId) {}

    public record WriteFlowReq(String projectId, String oldUserId, String newUserId) {}

    public record MatrixReq(List<String> projectIds, List<String> userIds) {}

    public record BatchWriteReq(String projectId, List<String> taskIds, String userId) {}

    public record BatchCheckReq(String projectId, List<String> taskIds, String userId) {}

}
