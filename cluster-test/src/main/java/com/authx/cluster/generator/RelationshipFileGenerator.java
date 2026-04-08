package com.authx.cluster.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

import static com.authx.cluster.generator.DataModel.*;

/**
 * Generates a text file containing ~10M SpiceDB relationships for the
 * document-library permission model defined in {@code deploy/schema-v2.zed}.
 *
 * <p>Each line follows the canonical tuple format:
 * {@code resource_type:resource_id#relation@subject_type:subject_id}
 *
 * <p>Generation proceeds in three phases:
 * <ol>
 *   <li><b>Base entities</b> — departments, groups, organizations (~24,500 rels)</li>
 *   <li><b>Spaces + Folders</b> — hierarchy with ancestor flattening (~1,730,000 rels)</li>
 *   <li><b>Documents + Permissions</b> — bulk of the dataset (~8,250,000 rels)</li>
 * </ol>
 */
@Component
public class RelationshipFileGenerator {

    private static final Logger log = LoggerFactory.getLogger(RelationshipFileGenerator.class);

    private static final int PROGRESS_INTERVAL = 100_000;

    /**
     * Generates the relationship file and returns the total relationship count.
     *
     * @param outputFile destination path for the generated file
     * @return total number of relationships written
     * @throws IOException if writing fails
     */
    public long generate(Path outputFile) throws IOException {
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        log.info("Generating relationships to {}", outputFile);

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
            long count = 0;
            count = generateBaseEntities(writer, count);
            count = generateSpacesAndFolders(writer, count);
            count = generateDocuments(writer, count);

            log.info("Generation complete — {} total relationships", count);
            return count;
        }
    }

    // ── Phase 1: Base entities ─────────────────────────────────

    private long generateBaseEntities(BufferedWriter writer, long count) throws IOException {
        log.info("Phase 1: Base entities (departments, groups, organizations)");
        count = generateDepartments(writer, count);
        count = generateGroups(writer, count);
        count = generateOrganizations(writer, count);
        log.info("Phase 1 complete — {} relationships so far", count);
        return count;
    }

    /**
     * Generates department hierarchy and user-to-department memberships.
     *
     * <p>500 departments arranged in 5 levels (100 per level). Level 0 are roots.
     * Each subsequent level picks a random parent from the previous level.
     * Each of the 10,000 users is assigned to exactly one random department.
     */
    private long generateDepartments(BufferedWriter writer, long count) throws IOException {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int deptsPerLevel = DEPT_COUNT / DEPT_DEPTH; // 100

        // Build hierarchy — levels 1..4 get a parent from the previous level
        for (int level = 1; level < DEPT_DEPTH; level++) {
            int levelStart = level * deptsPerLevel;
            int prevLevelStart = (level - 1) * deptsPerLevel;
            for (int i = levelStart; i < levelStart + deptsPerLevel; i++) {
                int parentIdx = prevLevelStart + rng.nextInt(deptsPerLevel);
                count = writeLine(writer, count,
                        "department:" + deptId(i) + "#parent@department:" + deptId(parentIdx));
            }
        }

        // Assign each user to exactly one random department
        for (int u = 0; u < USER_COUNT; u++) {
            int deptIdx = rng.nextInt(DEPT_COUNT);
            count = writeLine(writer, count,
                    "department:" + deptId(deptIdx) + "#member@user:" + userId(u));
        }

        return count;
    }

    /**
     * Generates 200 groups with ~20 random members each (4,000 relationships).
     */
    private long generateGroups(BufferedWriter writer, long count) throws IOException {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int membersPerGroup = 20;

        for (int g = 0; g < GROUP_COUNT; g++) {
            for (int m = 0; m < membersPerGroup; m++) {
                int userIdx = rng.nextInt(USER_COUNT);
                count = writeLine(writer, count,
                        "group:" + groupId(g) + "#member@user:" + userId(userIdx));
            }
        }
        return count;
    }

    /**
     * Generates 10 organizations, each with 1,000 members and 10 admins.
     */
    private long generateOrganizations(BufferedWriter writer, long count) throws IOException {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int membersPerOrg = USER_COUNT / ORG_COUNT;  // 1,000
        int adminsPerOrg  = 10;

        for (int o = 0; o < ORG_COUNT; o++) {
            // Members — distribute users evenly, then pick from that slice
            int sliceStart = o * membersPerOrg;
            for (int m = 0; m < membersPerOrg; m++) {
                int userIdx = sliceStart + m;
                count = writeLine(writer, count,
                        "organization:" + orgId(o) + "#member@user:" + userId(userIdx));
            }
            // Admins — random users from the org's member slice
            for (int a = 0; a < adminsPerOrg; a++) {
                int userIdx = sliceStart + rng.nextInt(membersPerOrg);
                count = writeLine(writer, count,
                        "organization:" + orgId(o) + "#admin@user:" + userId(userIdx));
            }
        }
        return count;
    }

    // ── Phase 2: Spaces + Folders ──────────────────────────────

    private long generateSpacesAndFolders(BufferedWriter writer, long count) throws IOException {
        log.info("Phase 2: Spaces and folders");
        count = generateSpaces(writer, count);
        count = generateFolders(writer, count);
        log.info("Phase 2 complete — {} relationships so far", count);
        return count;
    }

    /**
     * Generates 1,000 spaces distributed across organizations.
     * Each space gets an org link, 10 members, 10 viewers, and 1 admin.
     */
    private long generateSpaces(BufferedWriter writer, long count) throws IOException {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int membersPerSpace = 10;
        int viewersPerSpace = 10;

        for (int s = 0; s < SPACE_COUNT; s++) {
            int orgIdx = s % ORG_COUNT;
            count = writeLine(writer, count,
                    "space:" + spaceId(s) + "#org@organization:" + orgId(orgIdx));

            for (int m = 0; m < membersPerSpace; m++) {
                count = writeLine(writer, count,
                        "space:" + spaceId(s) + "#member@user:" + userId(rng.nextInt(USER_COUNT)));
            }
            for (int v = 0; v < viewersPerSpace; v++) {
                count = writeLine(writer, count,
                        "space:" + spaceId(s) + "#viewer@user:" + userId(rng.nextInt(USER_COUNT)));
            }
            // 1 admin per space
            count = writeLine(writer, count,
                    "space:" + spaceId(s) + "#admin@user:" + userId(rng.nextInt(USER_COUNT)));
        }
        return count;
    }

    /**
     * Generates 50,000 folders in a tree structure with max depth 20.
     *
     * <p>The ancestor-flat model writes ALL ancestor relationships for each folder
     * (not just the direct parent). This is the key optimization described in
     * schema-v2.zed: SpiceDB dispatches ancestor->*_local checks in parallel.
     *
     * <p>Each folder also gets 2 editors and 2 viewers.
     */
    private long generateFolders(BufferedWriter writer, long count) throws IOException {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Track each folder's parent and depth for ancestor chain construction.
        // parentOf[i] = index of folder i's direct parent (-1 for roots).
        int[] parentOf = new int[FOLDER_COUNT];
        int[] depthOf  = new int[FOLDER_COUNT];
        // spaceOf[i] = which space folder i belongs to
        int[] spaceOf  = new int[FOLDER_COUNT];

        int foldersPerSpace = FOLDER_COUNT / SPACE_COUNT; // 50

        for (int f = 0; f < FOLDER_COUNT; f++) {
            int space = f / foldersPerSpace;
            if (space >= SPACE_COUNT) space = SPACE_COUNT - 1;
            spaceOf[f] = space;

            // Space relationship
            count = writeLine(writer, count,
                    "folder:" + folderId(f) + "#space@space:" + spaceId(space));

            // Determine parent within the same space's folder range
            int spaceRangeStart = space * foldersPerSpace;
            if (f == spaceRangeStart) {
                // First folder in this space — root, no parent
                parentOf[f] = -1;
                depthOf[f]  = 0;
            } else {
                // Pick a random existing folder in this space whose depth < max-1
                int parent;
                int attempts = 0;
                do {
                    parent = spaceRangeStart + rng.nextInt(f - spaceRangeStart);
                    attempts++;
                } while (depthOf[parent] >= FOLDER_MAX_DEPTH - 1 && attempts < 100);

                // Fallback to root if all attempts exceeded depth
                if (depthOf[parent] >= FOLDER_MAX_DEPTH - 1) {
                    parent = spaceRangeStart;
                }

                parentOf[f] = parent;
                depthOf[f]  = depthOf[parent] + 1;

                // Write ALL ancestor relationships (flat model)
                int ancestor = parent;
                while (ancestor >= 0) {
                    count = writeLine(writer, count,
                            "folder:" + folderId(f) + "#ancestor@folder:" + folderId(ancestor));
                    ancestor = parentOf[ancestor];
                }
            }

            // 2 editors, 2 viewers per folder
            for (int e = 0; e < 2; e++) {
                count = writeLine(writer, count,
                        "folder:" + folderId(f) + "#editor@user:" + userId(rng.nextInt(USER_COUNT)));
            }
            for (int v = 0; v < 2; v++) {
                count = writeLine(writer, count,
                        "folder:" + folderId(f) + "#viewer@user:" + userId(rng.nextInt(USER_COUNT)));
            }
        }

        return count;
    }

    // ── Phase 3: Documents + Permissions ───────────────────────

    private long generateDocuments(BufferedWriter writer, long count) throws IOException {
        log.info("Phase 3: Documents and permissions");
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int docsPerFolder   = DOC_COUNT / FOLDER_COUNT;  // 10
        int editorsPerDoc   = 2;
        int viewersPerDoc   = 5;

        for (int d = 0; d < DOC_COUNT; d++) {
            int folderIdx = d / docsPerFolder;
            if (folderIdx >= FOLDER_COUNT) folderIdx = FOLDER_COUNT - 1;

            int foldersPerSpace = FOLDER_COUNT / SPACE_COUNT;
            int spaceIdx = folderIdx / foldersPerSpace;
            if (spaceIdx >= SPACE_COUNT) spaceIdx = SPACE_COUNT - 1;

            String docRef = "document:" + docId(d);

            // Folder and space relationships
            count = writeLine(writer, count,
                    docRef + "#folder@folder:" + folderId(folderIdx));
            count = writeLine(writer, count,
                    docRef + "#space@space:" + spaceId(spaceIdx));

            // Owner — 1 random user
            count = writeLine(writer, count,
                    docRef + "#owner@user:" + userId(rng.nextInt(USER_COUNT)));

            // Editors — 2 random users
            for (int e = 0; e < editorsPerDoc; e++) {
                count = writeLine(writer, count,
                        docRef + "#editor@user:" + userId(rng.nextInt(USER_COUNT)));
            }

            // Viewers — 5 random users
            for (int v = 0; v < viewersPerDoc; v++) {
                count = writeLine(writer, count,
                        docRef + "#viewer@user:" + userId(rng.nextInt(USER_COUNT)));
            }

            // Commenter — 1 random user
            count = writeLine(writer, count,
                    docRef + "#commenter@user:" + userId(rng.nextInt(USER_COUNT)));

            // Link viewer — 50% of documents get public link access
            if (d % 2 == 0) {
                count = writeLine(writer, count,
                        docRef + "#link_viewer@user:*");
            }
        }

        log.info("Phase 3 complete — {} relationships so far", count);
        return count;
    }

    // ── Helpers ────────────────────────────────────────────────

    private long writeLine(BufferedWriter writer, long count, String line) throws IOException {
        writer.write(line);
        writer.newLine();
        long newCount = count + 1;
        if (newCount % PROGRESS_INTERVAL == 0) {
            log.info("  ... {} relationships written", newCount);
        }
        return newCount;
    }
}
