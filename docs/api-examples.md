# SDK API 调用示例 — 文档库场景

基于飞书文档库权限模型：`organization → space → folder → document`

## 初始化

```java
// 生产环境
var client = AuthCsesClient.builder()
        .connection(c -> c
                .target("dns:///spicedb.prod:50051")
                .presharedKey("my-secret-key")
                .tls(true)
                .requestTimeout(Duration.ofSeconds(5)))
        .cache(c -> c
                .enabled(true)
                .maxSize(100_000)
                .watchInvalidation(true))    // Watch 流实时失效缓存
        .features(f -> f
                .coalescing(true)            // 并发相同请求合并
                .telemetry(true)             // OTel 链路追踪
                .virtualThreads(true))       // Java 21 虚拟线程
        .extend(e -> e
                .policies(PolicyRegistry.builder()
                        .defaultPolicy(ResourcePolicy.builder()
                                .readConsistency(ReadConsistency.session()) // 写后读一致
                                .cache(CachePolicy.ofTtl(Duration.ofSeconds(30)))
                                .build())
                        .build()))
        .build();

// 获取 ResourceFactory（线程安全，可复用）
var doc  = client.on("document");
var folder = client.on("folder");
var space  = client.on("space");
var org    = client.on("organization");
var dept   = client.on("department");
var group  = client.on("group");
```

---

## 1. 组织与部门

```java
// 设置组织结构
org.grant("org-1", "admin", "ceo");
org.grantToSubjects("org-1", "member", "department:eng#all_members");

// 部门层级：eng → backend, frontend
dept.grant("eng", "member", "alice", "bob", "charlie");
dept.grantToSubjects("backend", "parent", "department:eng");
dept.grant("backend", "member", "alice", "bob");
dept.grantToSubjects("frontend", "parent", "department:eng");
dept.grant("frontend", "member", "charlie");

// 用户组
group.grant("tech-leads", "member", "alice");
group.grantToSubjects("tech-leads", "member", "department:backend#all_members");
```

---

## 2. 空间管理

```java
// 创建空间，绑定组织
space.grantToSubjects("eng-space", "org", "organization:org-1");
space.grant("eng-space", "owner", "alice");
space.grantToSubjects("eng-space", "member", "group:tech-leads#member");

// 检查：alice 能管理空间吗？
space.check("eng-space", "manage", "alice");  // true (owner)

// 谁能看这个空间？
space.subjects("eng-space", "view");
// → ["alice", "bob", ...] (owner + member 都能看)
```

---

## 3. 文件夹 — 层级继承

```java
// 文件夹挂到空间
folder.grantToSubjects("project-x", "space", "space:eng-space");
folder.grant("project-x", "editor", "bob");

// 子文件夹继承父文件夹权限
folder.grantToSubjects("project-x/docs", "parent", "folder:project-x");
folder.grant("project-x/docs", "viewer", "charlie");

// charlie 只有 docs 的 view 权限
folder.check("project-x/docs", "view", "charlie");     // true (直接 viewer)
folder.check("project-x/docs", "edit", "charlie");     // false
folder.check("project-x", "view", "charlie");          // false (没有父文件夹权限)

// bob 对子文件夹也有 edit 权限（继承）
folder.check("project-x/docs", "edit", "bob");         // true (从父文件夹继承)
```

---

## 4. 文档 CRUD 权限

### 创建文档 + 授权

```java
// 创建文档，挂到文件夹和空间
doc.batch()
        .grant("folder").toSubjects("folder:project-x/docs")
        .grant("space").toSubjects("space:eng-space")
        .grant("owner").to("alice")
        .grant("editor").to("bob")
        .execute();
```

### 权限检查 — 单个

```java
// alice 是 owner
doc.check("design-doc", "view", "alice");       // true
doc.check("design-doc", "edit", "alice");       // true
doc.check("design-doc", "delete", "alice");     // true (owner → manage → delete)

// bob 是 editor
doc.check("design-doc", "edit", "bob");         // true
doc.check("design-doc", "delete", "bob");       // false (editor 不能删)

// charlie 是文件夹 viewer，继承到文档
doc.check("design-doc", "view", "charlie");     // true (folder→view 继承)
doc.check("design-doc", "edit", "charlie");     // false
```

### 权限检查 — 批量（一次 gRPC 调用）

```java
// 一个用户的多个权限
var perms = doc.checkAll("design-doc", "alice", "view", "edit", "delete", "share");
perms.get("view");     // true
perms.get("delete");   // true
perms.toMap();         // {view=true, edit=true, delete=true, share=true}

// 一个权限 × 多个用户
var bulk = doc.resource("design-doc").check("edit").byAll("alice", "bob", "charlie");
bulk.allowed();        // ["alice", "bob"]
bulk.denied();         // ["charlie"]
bulk.allAllowed();     // false
bulk.allowedCount();   // 2

// N 用户 × M 权限（矩阵）
var matrix = doc.resource("design-doc")
        .checkAll("view", "edit", "delete")
        .byAll("alice", "bob", "charlie");
matrix.get("alice").get("delete");   // true
matrix.get("bob").get("delete");     // false
matrix.get("charlie").get("view");   // true
```

### 权限检查 — 带一致性

```java
// 强一致（跳过缓存，直接读 SpiceDB）
doc.check("design-doc", "edit", "bob", Consistency.full());

// 用写操作返回的 token 保证写后读一致
var result = doc.resource("design-doc").grant("viewer").to("dave");
boolean canView = doc.check("design-doc", "view", "dave",
        result.asConsistency());  // atLeast(writeToken)
```

---

## 5. 查询 — 谁有权限？什么资源能访问？

### 正向查询：谁能看这个文档？

```java
// 所有能 view 的用户
List<String> viewers = doc.subjects("design-doc", "view");
// → ["alice", "bob", "charlie", ...]

// 带 limit
List<String> top5 = doc.subjects("design-doc", "view", 5);

// 作为 Set
Set<String> viewerSet = doc.subjectSet("design-doc", "view");

// 数量
int count = doc.subjectCount("design-doc", "view");

// 是否有人能看
boolean hasViewers = doc.hasSubjects("design-doc", "view");
```

### 关系查询：谁持有什么角色？

```java
// 谁是 editor？
List<String> editors = doc.relatedUsers("design-doc", "editor");
// → ["bob"]

// 所有关系，按角色分组
Map<String, List<String>> relations = doc.allRelations("design-doc");
// → {owner=[alice], editor=[bob], viewer=[charlie], folder=[project-x/docs]}

// 关系数量
int totalRels = doc.relationCount("design-doc");
int editorCount = doc.relationCount("design-doc", "editor");
```

### 反向查询：用户能访问什么文档？

```java
// bob 能编辑的所有文档
List<String> editableDocs = doc.resources("edit", "bob");
// → ["design-doc", "api-spec", ...]

// 带 limit
List<String> top10 = doc.resources("edit", "bob", 10);

// 作为 Set
Set<String> editableSet = doc.resourceSet("edit", "bob");

// bob 能访问任何文档吗？
boolean hasAccess = doc.hasResources("view", "bob");
```

---

## 6. 授权变更

### 单个授权/撤销

```java
// 授权
doc.grant("design-doc", "viewer", "dave", "eve");
doc.grant("design-doc", "editor", "frank");

// 撤销
doc.revoke("design-doc", "viewer", "dave");

// 撤销所有关系
doc.revokeAll("design-doc", "eve");

// 撤销指定角色的关系
doc.revokeAll("design-doc", new String[]{"viewer", "editor"}, "frank");
```

### 批量原子操作（一次 gRPC 调用）

```java
// 转让所有权：alice → bob，原子操作
doc.resource("design-doc").batch()
        .revoke("owner").from("alice")
        .grant("owner").to("bob")
        .grant("editor").to("alice")  // 降级为 editor
        .execute();
```

### 授权给非用户主体

```java
// 部门全员可看
doc.grantToSubjects("design-doc", "viewer", "department:eng#all_members");

// 用户组可编辑
doc.grantToSubjects("design-doc", "editor", "group:tech-leads#member");

// 通配符：所有用户可看（公开文档）
doc.grantToSubjects("design-doc", "link_viewer", "user:*");

// 撤销公开
doc.resource("design-doc").revoke("link_viewer").fromSubjects("user:*");
```

---

## 7. 链接分享

```java
// 开启链接分享（任何人可看）
doc.grantToSubjects("design-doc", "link_viewer", "user:*");

// 链接分享升级为可编辑
doc.resource("design-doc").batch()
        .revoke("link_viewer").fromSubjects("user:*")
        .grant("link_editor").toSubjects("user:*")
        .execute();

// 关闭链接分享
doc.resource("design-doc").revoke("link_viewer", "link_editor").fromSubjects("user:*");
```

---

## 8. 调试 — 权限树展开

```java
// 为什么 charlie 能看这个文档？展开权限计算树
ExpandTree tree = doc.resource("design-doc").expand("view");

System.out.println(tree);
// ExpandTree{operation=union, children=[
//   ExpandTree{comment, children=[
//     ExpandTree{edit, children=[...]},
//     ExpandTree{commenter, subjects=[]}
//   ]},
//   ExpandTree{viewer, subjects=[charlie]},
//   ExpandTree{link_viewer, subjects=[]},
//   ExpandTree{folder->view, children=[...]}
// ]}

// 树的深度和叶子节点
tree.depth();           // 5
tree.leaves();          // [viewer:[charlie], editor:[bob], owner:[alice], ...]
tree.contains("charlie"); // true
```

---

## 9. 可观测性

```java
// Metrics 快照
var m = client.metrics().snapshot();
m.cacheHitRate();       // 0.91
m.cacheHits();          // 9100
m.cacheMisses();        // 900
m.totalRequests();      // 10000
m.errorRate();          // 0.001
m.latencyP50Ms();       // 0.8
m.latencyP99Ms();       // 5.2

// 事件订阅
client.eventBus().subscribe(SdkTypedEvent.CircuitOpened.class, event ->
        log.warn("Circuit opened for {}", event.resourceType()));

// 健康检查
var health = client.health();
health.isHealthy();         // true
health.spicedbLatencyMs();  // 3

// Watch 实时关系变更
client.onRelationshipChange(change -> {
    log.info("{} {}:{} #{} @{}:{}",
            change.operation(),     // TOUCH or DELETE
            change.resourceType(),  // "document"
            change.resourceId(),    // "design-doc"
            change.relation(),      // "editor"
            change.subjectType(),   // "user"
            change.subjectId());    // "bob"
});
```

---

## 10. Spring Boot 集成

```java
@Configuration
public class PermissionConfig {

    @Bean(destroyMethod = "close")
    public AuthCsesClient authCsesClient() {
        return AuthCsesClient.builder()
                .connection(c -> c
                        .target("dns:///spicedb:50051")
                        .presharedKey("${SPICEDB_KEY}"))
                .cache(c -> c.enabled(true).watchInvalidation(true))
                .build();
    }

    // 类型安全的 ResourceFactory，直接注入 Service
    @Bean
    public ResourceFactory documentPermission(AuthCsesClient client) {
        return client.on("document");
    }
}

@Service
public class DocumentService {

    private final ResourceFactory doc;

    public DocumentService(ResourceFactory doc) {
        this.doc = doc;
    }

    public DocumentDTO getDocument(String docId, String userId) {
        if (!doc.check(docId, "view", userId)) {
            throw new ForbiddenException("No view permission");
        }
        // ... fetch document
    }

    public void shareDocument(String docId, String userId, String targetUserId, String role) {
        if (!doc.check(docId, "share", userId)) {
            throw new ForbiddenException("No share permission");
        }
        doc.grant(docId, role, targetUserId);
    }

    public Map<String, Boolean> getPermissions(String docId, String userId) {
        return doc.checkAll(docId, userId, "view", "edit", "delete", "share");
    }

    public List<String> getAccessibleDocs(String userId) {
        return doc.resources("view", userId);
    }
}
```
