package com.authx.cluster.generator;

/**
 * Constants and ID generators for the cluster-test data model.
 *
 * <p>Entity counts are calibrated to produce ~10M relationships when fed
 * through {@link RelationshipFileGenerator}. The ID format follows the
 * SpiceDB convention {@code type-N} for easy debugging and grep-ability.
 */
public final class DataModel {

    // ── Entity counts ──────────────────────────────────────────

    public static final int USER_COUNT       = 10_000;
    public static final int DEPT_COUNT       = 500;
    public static final int DEPT_DEPTH       = 5;
    public static final int GROUP_COUNT      = 200;
    public static final int ORG_COUNT        = 10;
    public static final int SPACE_COUNT      = 1_000;
    public static final int FOLDER_COUNT     = 50_000;
    public static final int FOLDER_MAX_DEPTH = 20;
    public static final int DOC_COUNT        = 500_000;

    // ── ID generators ──────────────────────────────────────────

    public static String userId(int i)   { return "user-" + i; }
    public static String deptId(int i)   { return "dept-" + i; }
    public static String groupId(int i)  { return "group-" + i; }
    public static String orgId(int i)    { return "org-" + i; }
    public static String spaceId(int i)  { return "space-" + i; }
    public static String folderId(int i) { return "folder-" + i; }
    public static String docId(int i)    { return "doc-" + i; }

    private DataModel() {}
}
