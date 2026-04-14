package com.authx.clustertest.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streams ~10M relationships to a flat file: {resourceType:id#relation@subjectType:subjectId} per line.
 *
 * <p>Deterministic via fixed seed so re-running yields identical data.
 *
 * <p>Deduplication: SpiceDB's BulkImportRelationships uses CREATE semantics
 * (not TOUCH). A single duplicate anywhere in the stream aborts the import
 * with ALREADY_EXISTS. Because we pick subjects with replacement, duplicates
 * are guaranteed unless we dedupe. Each per-resource scope uses a local
 * HashSet to collect unique (resource, relation, subject) tuples before
 * flushing — memory stays bounded by the largest single scope (~1000 users
 * per org = a few KB).
 */
@Component
public class RelationshipFileGenerator {
    private static final Logger log = LoggerFactory.getLogger(RelationshipFileGenerator.class);
    private static final long SEED = 42L;

    public long generate(Path output, DataModel m) throws IOException {
        Files.createDirectories(output.getParent());
        var rng = new Random(SEED);
        var count = new AtomicLong();
        try (BufferedWriter w = Files.newBufferedWriter(output)) {
            generateOrgs(w, m, rng, count);
            generateGroups(w, m, rng, count);
            generateSpaces(w, m, rng, count);
            generateFolders(w, m, rng, count);
            generateDocuments(w, m, rng, count);
        }
        log.info("Generated {} unique relationships to {}", count.get(), output);
        return count.get();
    }

    private void writeRel(BufferedWriter w, String rt, String rid, String rel, String st, String sid) throws IOException {
        w.write(rt); w.write(":"); w.write(rid); w.write("#"); w.write(rel);
        w.write("@"); w.write(st); w.write(":"); w.write(sid); w.write("\n");
    }

    /** Emit up to {@code target} unique (type:id, rel, sid) tuples. */
    private int emitUniqueSubjects(BufferedWriter w, String rt, String rid, String rel,
                                    String st, int target, int subjectSpace, Random rng,
                                    AtomicLong c) throws IOException {
        Set<String> seen = new HashSet<>(Math.min(target * 2, subjectSpace));
        int cap = Math.min(target, subjectSpace);
        int written = 0;
        // After cap attempts we bail — ceiling is 2× to tolerate collisions without infinite loop.
        int budget = cap * 3;
        while (seen.size() < cap && budget-- > 0) {
            String sid = "user-" + rng.nextInt(subjectSpace);
            if (seen.add(sid)) {
                writeRel(w, rt, rid, rel, st, sid);
                c.incrementAndGet();
                written++;
            }
        }
        return written;
    }

    private void generateOrgs(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        for (int i = 0; i < m.organizations(); i++) {
            emitUniqueSubjects(w, "organization", "org-" + i, "member", "user", 1000, m.users(), rng, c);
            emitUniqueSubjects(w, "organization", "org-" + i, "admin", "user", 10, m.users(), rng, c);
        }
    }

    private void generateGroups(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        for (int i = 0; i < m.groups(); i++) {
            emitUniqueSubjects(w, "group", "grp-" + i, "member", "user", 20, m.users(), rng, c);
        }
    }

    private void generateSpaces(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        for (int i = 0; i < m.spaces(); i++) {
            emitUniqueSubjects(w, "space", "spc-" + i, "member", "user", 25, m.users(), rng, c);
            emitUniqueSubjects(w, "space", "spc-" + i, "viewer", "user", 4, m.users(), rng, c);
            emitUniqueSubjects(w, "space", "spc-" + i, "admin", "user", 1, m.users(), rng, c);
        }
    }

    private void generateFolders(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        int[] depth = new int[m.folders()];
        for (int i = 0; i < m.folders(); i++) {
            int parentIdx = i == 0 ? -1 : rng.nextInt(i);
            int d = parentIdx == -1 ? 0 : Math.min(depth[parentIdx] + 1, m.folderMaxDepth());
            depth[i] = d;
            emitUniqueSubjects(w, "folder", "fld-" + i, "viewer", "user", 4, m.users(), rng, c);
            emitUniqueSubjects(w, "folder", "fld-" + i, "editor", "user", 1, m.users(), rng, c);
            // Ancestor flattening — unique by construction (walking parent chain).
            Set<Integer> ancestors = new HashSet<>();
            int cur = parentIdx;
            int hops = 0;
            while (cur != -1 && hops < 30) {
                if (ancestors.add(cur)) {
                    writeRel(w, "folder", "fld-" + i, "ancestor", "folder", "fld-" + cur);
                    c.incrementAndGet();
                }
                cur = cur == 0 ? -1 : rng.nextInt(cur);
                hops++;
                if (rng.nextInt(100) < 30) break;
            }
        }
    }

    private void generateDocuments(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        for (int i = 0; i < m.documents(); i++) {
            // Each doc has exactly one folder + one space — unique by construction.
            writeRel(w, "document", "doc-" + i, "folder", "folder", "fld-" + rng.nextInt(m.folders())); c.incrementAndGet();
            writeRel(w, "document", "doc-" + i, "space", "space", "spc-" + rng.nextInt(m.spaces())); c.incrementAndGet();
            emitUniqueSubjects(w, "document", "doc-" + i, "viewer", "user", 8, m.users(), rng, c);
            emitUniqueSubjects(w, "document", "doc-" + i, "editor", "user", 2, m.users(), rng, c);
            emitUniqueSubjects(w, "document", "doc-" + i, "owner", "user", 1, m.users(), rng, c);
            if (rng.nextInt(2) == 0) {
                writeRel(w, "document", "doc-" + i, "link_viewer", "user", "*"); c.incrementAndGet();
            }
        }
    }
}
