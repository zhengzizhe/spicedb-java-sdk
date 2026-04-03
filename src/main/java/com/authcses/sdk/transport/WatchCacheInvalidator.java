package com.authcses.sdk.transport;

import com.authcses.sdk.cache.CheckCache;
import com.authzed.api.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Subscribes to SpiceDB Watch stream and invalidates cache entries
 * when relationships change.
 *
 * <p>Runs a background daemon thread that:
 * 1. Opens a server-streaming gRPC Watch call
 * 2. On each WatchResponse, extracts changed resources and invalidates cache
 * 3. Auto-reconnects on disconnect with backoff
 *
 * <p>This solves cross-instance cache staleness: when SDK instance A writes,
 * SpiceDB notifies all Watch subscribers (including SDK instance B) of the change.
 */
public class WatchCacheInvalidator implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(WatchCacheInvalidator.class.getName());

    private final CheckCache cache;
    private final ManagedChannel channel;
    private final Metadata authMetadata;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<String> lastToken = new AtomicReference<>(null);
    private final Thread watchThread;

    public WatchCacheInvalidator(List<String> endpoints, String presharedKey, boolean useTls,
                                  CheckCache cache) {
        this.cache = cache;

        String target = endpoints.getFirst();
        var builder = ManagedChannelBuilder.forTarget(target);
        if (!useTls) builder.usePlaintext();
        this.channel = builder.build();

        this.authMetadata = new Metadata();
        authMetadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + presharedKey);

        this.watchThread = new Thread(this::watchLoop, "authcses-sdk-watch");
        this.watchThread.setDaemon(true);
        this.watchThread.start();
    }

    private void watchLoop() {
        long backoffMs = 1000;
        while (running.get()) {
            try {
                var requestBuilder = WatchRequest.newBuilder();
                String token = lastToken.get();
                if (token != null) {
                    requestBuilder.setOptionalStartCursor(
                            ZedToken.newBuilder().setToken(token).build());
                }

                var stub = WatchServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authMetadata));

                Iterator<WatchResponse> stream = stub.watch(requestBuilder.build());

                LOG.log(System.Logger.Level.INFO, "Watch stream connected");
                backoffMs = 1000; // reset backoff on successful connection

                while (stream.hasNext() && running.get()) {
                    WatchResponse response = stream.next();

                    // Track token for reconnection
                    if (response.hasChangesThrough()) {
                        lastToken.set(response.getChangesThrough().getToken());
                    }

                    // Invalidate cache for changed resources
                    for (var update : response.getUpdatesList()) {
                        var rel = update.getRelationship();
                        String resourceType = rel.getResource().getObjectType();
                        String resourceId = rel.getResource().getObjectId();
                        cache.invalidateResource(resourceType, resourceId);
                    }
                }
            } catch (Exception e) {
                if (!running.get()) return;
                LOG.log(System.Logger.Level.WARNING,
                        "Watch stream disconnected, reconnecting in {0}ms: {1}",
                        backoffMs, e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, 30_000); // max 30s backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        watchThread.interrupt();
        try {
            channel.shutdown().awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }

    public boolean isRunning() {
        return running.get() && watchThread.isAlive();
    }
}
