package com.authx.testapp.schema;

import com.authx.sdk.model.SubjectType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the regenerated schema classes' {@code subjectTypes()} metadata
 * against future codegen regressions. Every assertion maps directly to a
 * line of {@code deploy/schema.zed}, so a bug in the generator (missing
 * wildcard, wrong subject relation, dropped subject type) surfaces here
 * before business code can pick up a broken enum.
 */
class SubjectTypeRegressionTest {

    @Test
    void documentFolderRelationIsSingleTypedFolder() {
        assertThat(Document.Rel.FOLDER.subjectTypes())
                .containsExactly(SubjectType.of("folder"));
    }

    @Test
    void documentOwnerIsUserOnly() {
        assertThat(Document.Rel.OWNER.subjectTypes())
                .containsExactly(SubjectType.of("user"));
    }

    @Test
    void documentViewerIsUserGroupDeptWildcard() {
        assertThat(Document.Rel.VIEWER.subjectTypes())
                .containsExactly(
                        SubjectType.of("user"),
                        SubjectType.of("group", "member"),
                        SubjectType.of("department", "all_members"),
                        SubjectType.wildcard("user"));
    }

    @Test
    void documentLinkViewerIsWildcardOnly() {
        assertThat(Document.Rel.LINK_VIEWER.subjectTypes())
                .containsExactly(SubjectType.wildcard("user"));
    }

    @Test
    void folderParentIsSingleTypedFolder() {
        assertThat(Folder.Rel.PARENT.subjectTypes())
                .containsExactly(SubjectType.of("folder"));
    }

    @Test
    void groupMemberAllowsUserOrDepartment() {
        assertThat(Group.Rel.MEMBER.subjectTypes())
                .containsExactly(
                        SubjectType.of("user"),
                        SubjectType.of("department", "all_members"));
    }

    @Test
    void departmentParentIsSingleTypedDepartment() {
        assertThat(Department.Rel.PARENT.subjectTypes())
                .containsExactly(SubjectType.of("department"));
    }
}
