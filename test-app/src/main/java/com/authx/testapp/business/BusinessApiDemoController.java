package com.authx.testapp.business;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.WriteCompletion;
import com.authx.sdk.WriteListenerStage;
import com.authx.sdk.model.CheckMatrix;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.ExpandTree;
import com.authx.sdk.model.Tuple;
import com.authx.testapp.schema.Project;
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
import java.util.concurrent.CompletableFuture;

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
        // Authx 链含义：在 Project 类型中选中一个项目，把 MEMBER 关系授予指定 User，然后 commit 写入 SpiceDB。
        client.on(Project)
                .select(req.projectId())
                .grant(Project.Rel.MEMBER)
                .to(User, req.userId())
                .commit();
    }

    @PostMapping("/grant-subject-set")
    public void grantSubjectSet(@RequestBody ProjectOrg req) {
        // Authx 链含义：把项目 MEMBER 关系授予一个主体集合，即 org:{id}#member 中的所有成员。
        client.on(Project)
                .select(req.projectId())
                .grant(Project.Rel.MEMBER)
                .to(Org, req.orgId(), Org.Rel.MEMBER)
                .commit();
    }

    @PostMapping("/grant-wildcard")
    public void grantWildcard(@RequestBody ProjectOnly req) {
        // Authx 链含义：把项目 MEMBER 关系授予 User 类型的通配主体，相当于允许所有 user:* 成为成员。
        client.on(Project)
                .select(req.projectId())
                .grant(Project.Rel.MEMBER)
                .toWildcard(User)
                .commit();
    }

    @PostMapping("/write-flow")
    public void writeFlow(@RequestBody WriteFlowReq req) {
        // Authx 链含义：同一个写入流先撤销旧用户 MEMBER，再授予新用户 MANAGER，最后一次 commit 原子提交。
        client.on(Project)
                .select(req.projectId())
                .revoke(Project.Rel.MEMBER)
                .from(User, req.oldUserId())
                .grant(Project.Rel.MANAGER)
                .to(User, req.newUserId())
                .commit();
    }

    @PostMapping("/listener")
    public CompletableFuture<WriteCompletion> listener(@RequestBody ProjectUser req) {
        // Authx 链含义：listener 是异步写入监听阶段；该阶段的 commit 返回 Future。
        WriteListenerStage listener = client.on(Project)
                .select(req.projectId())
                .grant(Project.Rel.MEMBER)
                .to(User, req.userId())
                .listener(done -> log.info("project member granted, count={}", done.count()));

        return listener.commit();
    }

    @PostMapping("/check")
    public boolean check(@RequestBody ProjectUser req) {
        // Authx 链含义：检查指定 User 是否对选中的 Project 拥有 VIEW 权限，返回简单 boolean。
        return client.on(Project)
                .select(req.projectId())
                .check(Project.Perm.VIEW)
                .by(User, req.userId());
    }

    @PostMapping("/check-detailed")
    public CheckResult checkDetailed(@RequestBody ProjectUser req) {
        // Authx 链含义：执行同样的 VIEW 权限检查，但 detailedBy 返回包含 permissionship 等细节的结果。
        return client.on(Project)
                .select(req.projectId())
                .check(Project.Perm.VIEW)
                .detailedBy(User, req.userId());
    }

    @PostMapping("/check-all")
    public EnumMap<Project.Perm, Boolean> checkAll(@RequestBody ProjectUser req) {
        // Authx 链含义：针对一个 Project 和一个 User，一次检查 Project 枚举里的全部权限。
        return client.on(Project)
                .select(req.projectId())
                .checkAll()
                .by(User, req.userId());
    }

    @PostMapping("/check-matrix")
    public List<CheckMatrix.Cell> checkMatrix(@RequestBody MatrixReq req) {
        // Authx 链含义：对多个 Project、多个权限、多个 User 组成矩阵批量检查，并展开为 Cell 列表。
        return client.on(Project)
                .select(req.projectIds())
                .check(Project.Perm.VIEW, Project.Perm.MANAGE)
                .byAll(User, req.userIds())
                .cells();
    }

    @PostMapping("/batch-write")
    public void batchWrite(@RequestBody BatchWriteReq req) {
        // Authx 链含义：batch() 收集多个资源类型的关系写入；on/onAll 切换目标资源，最后统一 commit。
        client.batch()
                .on(Project, req.projectId())
                    .grant(Project.Rel.MEMBER).to(User, req.userId())
                .onAll(Task, req.taskIds())
                    .grant(Task.Rel.REVIEWER).to(User, req.userId())
                .commit();
    }

    @PostMapping("/batch-check")
    public List<CheckMatrix.Cell> batchCheck(@RequestBody BatchCheckReq req) {
        // Authx 链含义：batchCheck() 手动追加多个检查项，fetch 执行后用 cells() 读取矩阵结果。
        return client.batchCheck()
                .add(Project, req.projectId(), Project.Perm.VIEW, User, req.userId())
                .addAll(Task, req.taskIds(), Task.Perm.EDIT, User, req.userId())
                .fetch()
                .cells();
    }

    @PostMapping("/lookup-resources")
    public List<String> lookupResources(@RequestBody UserOnly req) {
        // Authx 链含义：从 User 反查其可 VIEW 的 Project，limit 限制最多返回 100 个资源 ID。
        return client.on(Project)
                .lookupResources(User, req.userId())
                .limit(100)
                .can(Project.Perm.VIEW);
    }

    @PostMapping("/lookup-resources-by-permissions")
    public Map<Project.Perm, List<String>> lookupResourcesByPermissions(@RequestBody UserOnly req) {
        // Authx 链含义：同一个 User 按权限分别反查 Project，返回每个权限对应的资源 ID 列表。
        return client.on(Project)
                .lookupResources(User, req.userId())
                .can(Project.Perm.VIEW, Project.Perm.MANAGE);
    }

    @PostMapping("/lookup-resources-any")
    public List<String> lookupResourcesAny(@RequestBody UserOnly req) {
        // Authx 链含义：反查 User 至少拥有 VIEW 或 MANAGE 其中一个权限的 Project。
        return client.on(Project)
                .lookupResources(User, req.userId())
                .canAny(Project.Perm.VIEW, Project.Perm.MANAGE);
    }

    @PostMapping("/lookup-resources-all")
    public List<String> lookupResourcesAll(@RequestBody UserOnly req) {
        // Authx 链含义：反查 User 同时拥有 VIEW 和 MANAGE 两个权限的 Project。
        return client.on(Project)
                .lookupResources(User, req.userId())
                .canAll(Project.Perm.VIEW, Project.Perm.MANAGE);
    }

    @PostMapping("/lookup-resources-by-subjects")
    public Map<String, List<String>> lookupResourcesBySubjects(@RequestBody UsersOnly req) {
        // Authx 链含义：对多个 User 分别反查其可 VIEW 的 Project，返回 userId 到资源 ID 列表的映射。
        return client.on(Project)
                .lookupResources(User, req.userIds())
                .can(Project.Perm.VIEW);
    }

    @PostMapping("/lookup-subjects")
    public List<String> lookupSubjects(@RequestBody ProjectOnly req) {
        // Authx 链含义：在选中的 Project 上反查哪些 User 拥有 VIEW 权限，并取前 100 个主体 ID。
        return client.on(Project)
                .select(req.projectId())
                .lookupSubjects(User, Project.Perm.VIEW)
                .limit(100)
                .fetchIds();
    }

    @PostMapping("/lookup-subjects-exists")
    public boolean lookupSubjectsExists(@RequestBody ProjectOnly req) {
        // Authx 链含义：只判断是否存在任意 User 对该 Project 拥有 VIEW 权限，不拉取完整主体列表。
        return client.on(Project)
                .select(req.projectId())
                .lookupSubjects(User, Project.Perm.VIEW)
                .exists();
    }

    @PostMapping("/relations")
    public List<Tuple> relations(@RequestBody ProjectOnly req) {
        // Authx 链含义：读取选中 Project 上的全部直接关系元组，返回原始 Tuple 列表。
        return client.on(Project)
                .select(req.projectId())
                .relations()
                .fetch();
    }

    @PostMapping("/relations-grouped")
    public Map<String, List<String>> relationsGrouped(@RequestBody ProjectOnly req) {
        // Authx 链含义：只读取 OWNER/MANAGER/MEMBER 关系，并按关系名分组为 relation -> subjects。
        return client.on(Project)
                .select(req.projectId())
                .relations(Project.Rel.OWNER, Project.Rel.MANAGER, Project.Rel.MEMBER)
                .groupByRelation();
    }

    @PostMapping("/expand")
    public ExpandTree expand(@RequestBody ProjectOnly req) {
        // Authx 链含义：展开选中 Project 的 VIEW 权限树，用于查看权限由哪些关系和主体推导而来。
        return client.on(Project)
                .select(req.projectId())
                .expand(Project.Perm.VIEW);
    }

    @PostMapping("/consistency")
    public boolean consistency(@RequestBody ProjectUser req) {


        // Authx 链含义：检查 VIEW 权限时显式使用 full consistency，要求从最新可见状态读取。
        return client.on(Project)
                .select(req.projectId())
                .check(Project.Perm.VIEW)
                .withConsistency(Consistency.full())
                .by(User, req.userId());
    }

    @PostMapping("/dynamic")
    public boolean dynamic(@RequestBody ProjectUser req) {
        // Authx 链含义：动态字符串 API 与 typed API 语义一致，直接使用 type/id/permission/subject 字符串。
        return client.on("project")
                .select(req.projectId())
                .check("view")
                .by("user:" + req.userId());
    }

    @GetMapping("/health")
    public Object health() {
        // Authx 链含义：health() 不是权限链，而是读取 SDK/底层传输的健康检查结果。
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
