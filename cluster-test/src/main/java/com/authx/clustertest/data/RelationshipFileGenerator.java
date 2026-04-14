package com.authx.clustertest.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streams 10M+ relationships to a flat file: {resourceType:id#relation@subjectType:subjectId} per line.
 *
 * <p>Deterministic via fixed seed so re-running yields identical data.
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
        log.info("Generated {} relationships to {}", count.get(), output);
        return count.get();
    }

    private void writeRel(BufferedWriter w, String rt, String rid, String rel, String st, String sid) throws IOException {
        w.write(rt); w.write(":"); w.write(rid); w.write("#"); w.write(rel);
        w.write("@"); w.write(st); w.write(":"); w.write(sid); w.write("\n");
    }

    private void generateOrgs(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        for (int i = 0; i < m.organizations(); i++) {
            for (int j = 0; j < 1000; j++) {
                writeRel(w, "organization", "org-" + i, "member", "user", "user-" + rng.nextInt(m.users()));
                c.incrementAndGet();
            }
            for (int j = 0; j < 10; j++) {
                writeRel(w, "organization", "org-" + i, "admin", "user", "user-" + rng.nextInt(m.users()));
                c.incrementAndGet();
            }
        }
    }

    private void generateGroups(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        for (int i = 0; i < m.groups(); i++) {
            for (int j = 0; j < 20; j++) {
                writeRel(w, "group", "grp-" + i, "member", "user", "user-" + rng.nextInt(m.users()));
                c.incrementAndGet();
            }
        }
    }

    private void generateSpaces(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        for (int i = 0; i < m.spaces(); i++) {
            for (int j = 0; j < 25; j++) {
                writeRel(w, "space", "spc-" + i, "member", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            }
            for (int j = 0; j < 4; j++) {
                writeRel(w, "space", "spc-" + i, "viewer", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            }
            writeRel(w, "space", "spc-" + i, "admin", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
        }
    }

    private void generateFolders(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        // Track depth per folder so we cap inheritance depth.
        int[] depth = new int[m.folders()];
        for (int i = 0; i < m.folders(); i++) {
            int parentIdx = i == 0 ? -1 : rng.nextInt(i);
            int d = parentIdx == -1 ? 0 : Math.min(depth[parentIdx] + 1, m.folderMaxDepth());
            depth[i] = d;
            for (int j = 0; j < 4; j++) {
                writeRel(w, "folder", "fld-" + i, "viewer", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            }
            writeRel(w, "folder", "fld-" + i, "editor", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            // Ancestor flattening: walk a few levels up emitting ancestor relations.
            int cur = parentIdx;
            int hops = 0;
            while (cur != -1 && hops < 30) {
                writeRel(w, "folder", "fld-" + i, "ancestor", "folder", "fld-" + cur); c.incrementAndGet();
                cur = cur == 0 ? -1 : rng.nextInt(cur);
                hops++;
                if (rng.nextInt(100) < 30) break;
            }
        }
    }

    private void generateDocuments(BufferedWriter w, DataModel m, Random rng, AtomicLong c) throws IOException {
        for (int i = 0; i < m.documents(); i++) {
            writeRel(w, "document", "doc-" + i, "folder", "folder", "fld-" + rng.nextInt(m.folders())); c.incrementAndGet();
            writeRel(w, "document", "doc-" + i, "space", "space", "spc-" + rng.nextInt(m.spaces())); c.incrementAndGet();
            for (int j = 0; j < 8; j++) {
                writeRel(w, "document", "doc-" + i, "viewer", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            }
            for (int j = 0; j < 2; j++) {
                writeRel(w, "document", "doc-" + i, "editor", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            }
            writeRel(w, "document", "doc-" + i, "owner", "user", "user-" + rng.nextInt(m.users())); c.incrementAndGet();
            if (rng.nextInt(2) == 0) {
                writeRel(w, "document", "doc-" + i, "link_viewer", "user", "*"); c.incrementAndGet();
            }
        }
    }
}
