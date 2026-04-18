package com.authx.clustertest.data;

import com.authx.clustertest.config.ClusterProps;
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
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Streams a relationship text file into SpiceDB via the BulkImportRelationships gRPC API.
 * Bypasses the SDK — uses the authzed gRPC stub directly to keep import throughput high.
 */
@Component
public class BulkImporter {
    private static final Logger log = LoggerFactory.getLogger(BulkImporter.class);
    private static final int BATCH = 1000;
    private static final Pattern LINE = Pattern.compile("^([^:]+):([^#]+)#([^@]+)@([^:]+):(.+)$");

    private final ClusterProps props;

    public BulkImporter(ClusterProps props) { this.props = props; }

    public long importFile(Path file) throws Exception {
        var addr = props.spicedb().targets().split(",")[0];
        ManagedChannel ch = ManagedChannelBuilder.forTarget(addr).usePlaintext()
                .maxInboundMessageSize(64 * 1024 * 1024).build();
        try {
            var meta = new Metadata();
            meta.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + props.spicedb().presharedKey());
            var stub = ExperimentalServiceGrpc.newStub(ch)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(meta));

            var done = new CountDownLatch(1);
            var counted = new AtomicLong();
            var firstError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            StreamObserver<BulkImportRelationshipsResponse> resp = new StreamObserver<>() {
                public void onNext(BulkImportRelationshipsResponse r) { counted.addAndGet(r.getNumLoaded()); }
                public void onError(Throwable t) {
                    log.error("Bulk import failed", t);
                    firstError.compareAndSet(null, t);
                    done.countDown();
                }
                public void onCompleted() { done.countDown(); }
            };
            StreamObserver<BulkImportRelationshipsRequest> req = stub.bulkImportRelationships(resp);

            var batch = new ArrayList<Relationship>(BATCH);
            try (var lines = Files.lines(file)) {
                lines.forEach(line -> {
                    var rel = parse(line);
                    if (rel != null) batch.add(rel);
                    if (batch.size() >= BATCH) {
                        req.onNext(BulkImportRelationshipsRequest.newBuilder()
                                .addAllRelationships(batch).build());
                        batch.clear();
                    }
                });
            }
            if (!batch.isEmpty()) {
                req.onNext(BulkImportRelationshipsRequest.newBuilder()
                        .addAllRelationships(batch).build());
            }
            req.onCompleted();
            done.await(10, TimeUnit.MINUTES);
            if (firstError.get() != null) {
                throw new RuntimeException("Bulk import failed after " + counted.get()
                        + " relationships: " + firstError.get().getMessage(), firstError.get());
            }
            log.info("Imported {} relationships", counted.get());
            return counted.get();
        } finally {
            ch.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private Relationship parse(String line) {
        var m = LINE.matcher(line.trim());
        if (!m.matches()) return null;
        return Relationship.newBuilder()
                .setResource(ObjectReference.newBuilder()
                        .setObjectType(m.group(1)).setObjectId(m.group(2)))
                .setRelation(m.group(3))
                .setSubject(SubjectReference.newBuilder()
                        .setObject(ObjectReference.newBuilder()
                                .setObjectType(m.group(4)).setObjectId(m.group(5))))
                .build();
    }
}
