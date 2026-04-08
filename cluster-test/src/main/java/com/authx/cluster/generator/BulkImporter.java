package com.authx.cluster.generator;

import com.authzed.api.v1.BulkImportRelationshipsRequest;
import com.authzed.api.v1.BulkImportRelationshipsResponse;
import com.authzed.api.v1.ExperimentalServiceGrpc;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.Relationship;
import com.authzed.api.v1.SubjectReference;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams relationship files into SpiceDB via the BulkImportRelationships
 * streaming gRPC API.
 *
 * <p>Opens a dedicated channel to the <em>first</em> SpiceDB target so that
 * every relationship in a single import lands on one node (BulkImport must
 * not be round-robined across a cluster).
 */
@Component
public class BulkImporter {

    private static final Logger LOG = LoggerFactory.getLogger(BulkImporter.class);

    /** Regex for the relationship line format: {@code resource_type:resource_id#relation@subject_type:subject_id}. */
    private static final Pattern LINE_PATTERN =
            Pattern.compile("^(\\w+):([^#]+)#(\\w+)@(\\w+):(.+)$");

    /** Number of relationships accumulated before sending a single request message. */
    private static final int BATCH_SIZE = 1_000;

    /** Log a progress line every N relationships read. */
    private static final int LOG_INTERVAL = 100_000;

    private final String targets;
    private final String presharedKey;

    public BulkImporter(
            @Value("${spicedb.targets}") String targets,
            @Value("${spicedb.preshared-key}") String presharedKey) {
        this.targets = targets;
        this.presharedKey = presharedKey;
    }

    /**
     * Import all relationships from {@code file} into SpiceDB.
     *
     * @param file path to a text file with one relationship per line
     * @return result containing the number of imported relationships and wall-clock duration
     * @throws IOException          if the file cannot be read
     * @throws InterruptedException if the calling thread is interrupted while waiting for the server response
     */
    public ImportResult importFile(Path file) throws IOException, InterruptedException {
        String target = targets.split(",")[0].trim();
        LOG.info("Opening BulkImport channel to {}", target);

        ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();

        try {
            return doImport(channel, file);
        } finally {
            channel.shutdown();
            if (!channel.awaitTermination(10, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        }
    }

    // ── Internal ────────────────────────────────────────────────

    private ImportResult doImport(ManagedChannel channel, Path file)
            throws IOException, InterruptedException {

        // Auth metadata — same pattern as SchemaLoader
        Metadata authMeta = new Metadata();
        authMeta.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + presharedKey);

        ExperimentalServiceGrpc.ExperimentalServiceStub stub =
                ExperimentalServiceGrpc.newStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authMeta));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong totalLoaded = new AtomicLong();
        AtomicLong errorFlag = new AtomicLong(); // 0 = success, 1 = error

        StreamObserver<BulkImportRelationshipsResponse> responseObserver =
                new StreamObserver<>() {
                    @Override
                    public void onNext(BulkImportRelationshipsResponse response) {
                        totalLoaded.set(response.getNumLoaded());
                        LOG.info("BulkImport server confirmed {} relationships loaded",
                                response.getNumLoaded());
                    }

                    @Override
                    public void onError(Throwable t) {
                        LOG.error("BulkImport stream error: {}", t.getMessage(), t);
                        errorFlag.set(1);
                        latch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        LOG.info("BulkImport stream completed");
                        latch.countDown();
                    }
                };

        StreamObserver<BulkImportRelationshipsRequest> requestObserver =
                stub.bulkImportRelationships(responseObserver);

        long startMs = System.currentTimeMillis();
        long lineCount = 0;
        List<Relationship> batch = new ArrayList<>(BATCH_SIZE);

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Relationship rel = parseLine(line);
                if (rel == null) {
                    continue; // skip malformed lines
                }
                batch.add(rel);
                lineCount++;

                if (batch.size() >= BATCH_SIZE) {
                    sendBatch(requestObserver, batch);
                    batch.clear();
                }

                if (lineCount % LOG_INTERVAL == 0) {
                    LOG.info("Read {} lines from {}", lineCount, file.getFileName());
                }
            }
        }

        // Send remaining relationships
        if (!batch.isEmpty()) {
            sendBatch(requestObserver, batch);
            batch.clear();
        }

        LOG.info("Finished reading {} lines, completing stream", lineCount);
        requestObserver.onCompleted();

        // Wait for the server response
        latch.await();

        long durationMs = System.currentTimeMillis() - startMs;
        long imported = totalLoaded.get();
        LOG.info("BulkImport finished: {} imported in {} ms", imported, durationMs);

        return new ImportResult(imported, durationMs);
    }

    private static Relationship parseLine(String line) {
        Matcher m = LINE_PATTERN.matcher(line.trim());
        if (!m.matches()) {
            return null;
        }
        String resType  = m.group(1);
        String resId    = m.group(2);
        String relation = m.group(3);
        String subType  = m.group(4);
        String subId    = m.group(5);

        return Relationship.newBuilder()
                .setResource(ObjectReference.newBuilder()
                        .setObjectType(resType)
                        .setObjectId(resId))
                .setRelation(relation)
                .setSubject(SubjectReference.newBuilder()
                        .setObject(ObjectReference.newBuilder()
                                .setObjectType(subType)
                                .setObjectId(subId)))
                .build();
    }

    private static void sendBatch(
            StreamObserver<BulkImportRelationshipsRequest> requestObserver,
            List<Relationship> batch) {
        BulkImportRelationshipsRequest request =
                BulkImportRelationshipsRequest.newBuilder()
                        .addAllRelationships(batch)
                        .build();
        requestObserver.onNext(request);
    }

    // ── Result record ───────────────────────────────────────────

    /**
     * Result of a bulk import operation.
     *
     * @param imported   number of relationships the server confirmed as loaded
     * @param durationMs wall-clock time of the import in milliseconds
     */
    public record ImportResult(long imported, long durationMs) {}
}
