package com.authcses.testapp;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.sdk.model.Consistency;

import java.util.*;

/**
 * 预计算的权限真值表。
 * 在压测前用 full consistency 逐条校验建表，
 * 压测时每个 check 结果都与真值表对比。
 *
 * 真值表保证：如果压测中有任何一条结果不一致，说明存在
 * 缓存脏数据、并发 bug、或 SpiceDB 一致性问题。
 */
public class TruthTable {

    public record CheckCase(String resourceType, String resourceId,
                            String permission, String userId, boolean expected) {}

    private final List<CheckCase> cases = new ArrayList<>();
    private final Map<String, Boolean> index = new HashMap<>(); // key → expected

    public List<CheckCase> cases() { return Collections.unmodifiableList(cases); }
    public int size() { return cases.size(); }

    /**
     * 从 DataSeeder 的确定性数据构建真值表。
     * 用 full consistency 逐条查 SpiceDB 获取真实值作为基准。
     */
    public static TruthTable build(AuthCsesClient client) {
        var table = new TruthTable();
        long start = System.currentTimeMillis();

        String[] users = DataSeeder.ALL_USERS;
        String[] permissions = {"view", "edit", "comment", "manage", "delete", "share"};
        String[] docs = {"design-doc", "api-spec", "secret-doc", "public-doc",
                "shared-edit-doc", "isolated-doc"};
        String[] folders = {"wiki-root", "backend-docs", "api-docs", "api-internal"};

        // Document permissions — 8 users × 6 docs × 6 permissions = 288 cases
        for (String user : users) {
            for (String doc : docs) {
                for (String perm : permissions) {
                    boolean expected = client.check("document", doc, perm, user, Consistency.full());
                    table.add("document", doc, perm, user, expected);
                }
            }
        }

        // Folder permissions — 8 users × 4 folders × 4 permissions = 128 cases
        String[] folderPerms = {"view", "edit", "comment", "manage"};
        for (String user : users) {
            for (String folder : folders) {
                for (String perm : folderPerms) {
                    boolean expected = client.check("folder", folder, perm, user, Consistency.full());
                    table.add("folder", folder, perm, user, expected);
                }
            }
        }

        // Space permissions — 8 users × 1 space × 3 permissions = 24 cases
        String[] spacePerms = {"view", "edit", "manage"};
        for (String user : users) {
            for (String perm : spacePerms) {
                boolean expected = client.check("space", "wiki", perm, user, Consistency.full());
                table.add("space", "wiki", perm, user, expected);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("Truth table built: %d cases in %dms (true=%d, false=%d)%n",
                table.size(), elapsed, table.trueCount(), table.falseCount());
        return table;
    }

    /** Public add — used by LargeScaleSeeder to build truth table from verified checks. */
    public void addDirect(String type, String id, String perm, String user, boolean expected) {
        add(type, id, perm, user, expected);
    }

    private void add(String type, String id, String perm, String user, boolean expected) {
        var c = new CheckCase(type, id, perm, user, expected);
        cases.add(c);
        index.put(key(type, id, perm, user), expected);
    }

    public boolean expected(String type, String id, String perm, String user) {
        Boolean v = index.get(key(type, id, perm, user));
        if (v == null) throw new IllegalArgumentException(
                "No truth entry for " + type + ":" + id + "#" + perm + "@" + user);
        return v;
    }

    public long trueCount() { return cases.stream().filter(c -> c.expected).count(); }
    public long falseCount() { return cases.stream().filter(c -> !c.expected).count(); }

    /** 按 expected=true 和 expected=false 分别采样，保证正反例均衡 */
    public List<CheckCase> balanced(int count, Random rng) {
        List<CheckCase> trues = cases.stream().filter(c -> c.expected).toList();
        List<CheckCase> falses = cases.stream().filter(c -> !c.expected).toList();

        List<CheckCase> result = new ArrayList<>(count);
        int half = count / 2;
        for (int i = 0; i < half; i++) {
            result.add(trues.get(rng.nextInt(trues.size())));
            result.add(falses.get(rng.nextInt(falses.size())));
        }
        Collections.shuffle(result, rng);
        return result;
    }

    private static String key(String type, String id, String perm, String user) {
        return type + ":" + id + "#" + perm + "@" + user;
    }
}
