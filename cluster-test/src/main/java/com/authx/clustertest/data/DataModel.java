package com.authx.clustertest.data;

public record DataModel(
        int users, int groups, int organizations, int spaces,
        int folders, int folderMaxDepth, int documents,
        long expectedRelationships
) {
    public static DataModel defaults() {
        return new DataModel(10_000, 200, 10, 1_000, 50_000, 20, 500_000, 10_000_000L);
    }

    /** Smaller dataset for smoke tests. */
    public static DataModel small() {
        return new DataModel(1_000, 50, 5, 100, 5_000, 10, 50_000, 1_000_000L);
    }
}
