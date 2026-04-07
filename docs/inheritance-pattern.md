# 继承与断开继承 — Schema 设计 + SDK 调用

## 核心设计

```
folder (结构) ≠ inherit_folder (权限继承)
```

| relation | 含义 | 删除后果 |
|----------|------|---------|
| `folder` | 文档在哪个文件夹里（结构） | 文档移出文件夹 |
| `inherit_folder` | 是否从文件夹继承权限 | 断开继承，文档独立管权限 |

**创建文档时两个都写 → 默认继承。断开继承时只删 `inherit_folder` → 文档还在文件夹里，但权限独立。**

---

## 场景 1：默认继承

```java
var doc = client.on("document");
var folder = client.on("folder");

// 文件夹：bob 是 editor
folder.grant("project-x", "editor", "bob");

// 创建文档：同时写 folder + inherit_folder（默认继承）
doc.resource("design-doc").batch()
        .grant("folder").toSubjects("folder:project-x")
        .grant("inherit_folder").toSubjects("folder:project-x")   // ← 继承开关
        .grant("owner").to("alice")
        .execute();

// bob 通过文件夹继承拿到 edit 权限
doc.check("design-doc", "edit", "bob");    // true ✓（从 folder 继承）
doc.check("design-doc", "view", "bob");    // true ✓
```

---

## 场景 2：断开继承

```java
// 断开继承：只删 inherit_folder，保留 folder（文档还在文件夹里）
doc.resource("design-doc").revoke("inherit_folder").fromSubjects("folder:project-x");

// bob 的文件夹权限不再穿透
doc.check("design-doc", "edit", "bob");    // false ✗（继承已断开）
doc.check("design-doc", "view", "bob");    // false ✗

// alice 的直接权限不受影响
doc.check("design-doc", "edit", "alice");  // true ✓（owner，直接权限）

// 现在需要单独给 bob 授权
doc.grant("design-doc", "viewer", "bob");
doc.check("design-doc", "view", "bob");    // true ✓（直接权限）
doc.check("design-doc", "edit", "bob");    // false ✗（只给了 viewer）
```

---

## 场景 3：恢复继承

```java
// 恢复继承：重新写 inherit_folder
doc.grantToSubjects("design-doc", "inherit_folder", "folder:project-x");

// bob 的文件夹 editor 权限又回来了
doc.check("design-doc", "edit", "bob");    // true ✓

// 注意：之前单独给的 viewer 也还在（叠加）
```

---

## 场景 4：文件夹层级的继承控制

```java
// 文件夹结构：project-x → project-x/secret
folder.grantToSubjects("project-x/secret", "parent", "folder:project-x");

// 默认继承父文件夹
folder.grantToSubjects("project-x/secret", "inherit_parent", "folder:project-x");

// project-x 的 editor bob 能访问 secret 子文件夹
folder.check("project-x/secret", "edit", "bob");   // true ✓

// 断开子文件夹的继承（保密文件夹）
folder.resource("project-x/secret").revoke("inherit_parent").fromSubjects("folder:project-x");

// bob 不再能访问
folder.check("project-x/secret", "edit", "bob");   // false ✗
folder.check("project-x/secret", "view", "bob");   // false ✗

// 但 project-x 的权限不受影响
folder.check("project-x", "edit", "bob");           // true ✓
```

---

## 场景 5：封装成业务 API

```java
@Service
public class DocumentService {

    private final ResourceFactory doc;

    /** 创建文档（默认继承文件夹权限） */
    public void createDocument(String docId, String folderId, String spaceId, String ownerId) {
        doc.resource(docId).batch()
                .grant("folder").toSubjects("folder:" + folderId)
                .grant("space").toSubjects("space:" + spaceId)
                .grant("inherit_folder").toSubjects("folder:" + folderId)    // 默认继承
                .grant("inherit_space").toSubjects("space:" + spaceId)       // 默认继承
                .grant("owner").to(ownerId)
                .execute();
    }

    /** 断开继承（文档独立管权限） */
    public void breakInheritance(String docId, String folderId) {
        doc.resource(docId).revoke("inherit_folder").fromSubjects("folder:" + folderId);
    }

    /** 恢复继承 */
    public void restoreInheritance(String docId, String folderId) {
        doc.grantToSubjects(docId, "inherit_folder", "folder:" + folderId);
    }

    /** 查询文档是否继承了文件夹权限 */
    public boolean isInheriting(String docId) {
        return doc.hasRelations(docId);  // 简化写法
        // 精确写法：
        // return !doc.resource(docId).relations("inherit_folder").fetch().isEmpty();
    }

    /** 移动文档到新文件夹（保持继承设置） */
    public void moveDocument(String docId, String oldFolderId, String newFolderId) {
        // 检查当前是否继承
        boolean wasInheriting = !doc.resource(docId)
                .relations("inherit_folder").fetch().isEmpty();

        doc.resource(docId).batch()
                .revoke("folder").fromSubjects("folder:" + oldFolderId)
                .revoke("inherit_folder").fromSubjects("folder:" + oldFolderId)
                .grant("folder").toSubjects("folder:" + newFolderId)
                .execute();

        // 如果之前继承了，新文件夹也继承
        if (wasInheriting) {
            doc.grantToSubjects(docId, "inherit_folder", "folder:" + newFolderId);
        }
    }

    /** 复制文档（不复制继承关系，新文档独立） */
    public void copyDocument(String srcDocId, String newDocId, String folderId, String userId) {
        doc.resource(newDocId).batch()
                .grant("folder").toSubjects("folder:" + folderId)
                .grant("owner").to(userId)
                // 不写 inherit_folder → 新文档默认不继承
                .execute();
    }
}
```

---

## 场景 6：查看继承状态 + 有效权限来源

```java
// 调试：权限从哪来的？
var tree = doc.resource("design-doc").expand("view");

// 继承中的文档 → 树里有 inherit_folder->view 分支
// 断开继承的文档 → 树里只有直接 viewer/editor/owner

// 列出文档的所有关系，区分"结构"和"权限"
var rels = doc.allRelations("design-doc");
// 继承中：{owner=[alice], folder=[project-x], inherit_folder=[project-x]}
// 断开后：{owner=[alice], folder=[project-x], viewer=[bob]}
//                                              ↑ inherit_folder 消失了
```

---

## 对比

| 操作 | 继承中 | 断开后 |
|------|:------:|:------:|
| `folder` relation | ✓ 有 | ✓ 有（还在文件夹里） |
| `inherit_folder` relation | ✓ 有 | ✗ 没有 |
| 文件夹 viewer 能看文档？ | ✓ 能 | ✗ 不能 |
| 文档自身 viewer 能看？ | ✓ 能 | ✓ 能 |
| 文档在文件夹树中可见？ | ✓ 是 | ✓ 是 |

**一句话：`inherit_folder` 是一个开关 relation——有它就继承，删它就独立。不需要改 schema，不需要重建关系，只需要一次 `revoke` 调用。**
