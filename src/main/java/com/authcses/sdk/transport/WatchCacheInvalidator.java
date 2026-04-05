package com.authcses.sdk.transport;

import com.authcses.sdk.cache.Cache;
import com.authcses.sdk.cache.IndexedCache;
import com.authcses.sdk.model.CheckKey;
import com.authcses.sdk.model.CheckResult;
import com.authcses.sdk.model.RelationshipChange;
import com.authzed.api.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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

    private final Cache<CheckKey, CheckResult> cache;
    private final ManagedChannel channel;
    private final boolean ownsChannel;
    private final Metadata authMetadata;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<String> lastToken = new AtomicReference<>(null);
    private final Thread watchThread;
    private final List<Consumer<RelationshipChange>> listeners = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ExecutorService listenerExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "authcses-sdk-watch-dispatch");
                t.setDaemon(true);
                return t;
            });

    /** Register a listener for relationship changes. */
    public void addListener(Consumer<RelationshipChange> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<RelationshipChange> listener) {
        listeners.remove(listener);
    }

    /**
     * Create with a shared channel (preferred — reuses the main client's gRPC channel).
     */
    public WatchCacheInvalidator(ManagedChannel channel, String presharedKey, Cache<CheckKey, CheckResult> cache) {
        this.cache = cache;
        this.channel = channel;
        this.ownsChannel = false;

        this.authMetadata = new Metadata();
        authMetadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + presharedKey);

        this.watchThread = new Thread(this::watchLoop, "authcses-sdk-watch");
        this.watchThread.setDaemon(true);
        // Thread not started here — caller must call start()
    }

    /** Start the watch stream. Must be called after construction. */
    public void start() {
        if (watchThread.getState() == Thread.State.NEW) {
            watchThread.start();
        }
    }

    /**
     * @deprecated Use {@link #WatchCacheInvalidator(ManagedChannel, String, Cache)} instead.
     */
    @Deprecated
    public WatchCacheInvalidator(List<String> endpoints, String presharedKey, boolean useTls,
                                  Cache<CheckKey, CheckResult> cache) {
        this.cache = cache;

        String target = endpoints.getFirst();
        var builder = ManagedChannelBuilder.forTarget(target);
        if (!useTls) builder.usePlaintext();
        this.channel = builder.build();
        this.ownsChannel = true;

        this.authMetadata = new Metadata();
        authMetadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + presharedKey);

        this.watchThread = new Thread(this::watchLoop, "authcses-sdk-watch");
        this.watchThread.setDaemon(true);
        // Thread not started here — caller must call start()
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

                    String changeToken = response.hasChangesThrough()
                            ? response.getChangesThrough().getToken() : null;

                    for (var update : response.getUpdatesList()) {
                        var rel = update.getRelationship();
                        String resourceType = rel.getResource().getObjectType();
                        String resourceId = rel.getResource().getObjectId();

                        // 1. Invalidate cache
                        String indexKey = resourceType + ":" + resourceId;
                        if (cache instanceof IndexedCache<CheckKey, CheckResult> indexed) {
                            indexed.invalidateByIndex(indexKey);
                        } else {
                            cache.invalidateAll(key -> indexKey.equals(key.resourceIndex()));
                        }

                        // 2. Notify user listeners (async to avoid blocking watch thread)
                        if (!listeners.isEmpty()) {
                            var op = update.getOperation() == RelationshipUpdate.Operation.OPERATION_DELETE
                                    ? RelationshipChange.Operation.DELETE
                                    : RelationshipChange.Operation.TOUCH;
                            String subRel = rel.getSubject().getOptionalRelation();
                            var change = new RelationshipChange(
                                    op, resourceType, resourceId, rel.getRelation(),
                                    rel.getSubject().getObject().getObjectType(),
                                    rel.getSubject().getObject().getObjectId(),
                                    subRel.isEmpty() ? null : subRel, changeToken);
                            listenerExecutor.execute(() -> {
                                for (var listener : listeners) {
                                    try {
                                        listener.accept(change);
                                    } catch (Exception e) {
                                        LOG.log(System.Logger.Level.WARNING,
                                                "Watch listener error: {0}", e.getMessage());
                                    }
                                }
                            });
                        }
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
            watchThread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        listenerExecutor.shutdown();
        try {
            if (!listenerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                listenerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listenerExecutor.shutdownNow();
        }
        if (ownsChannel) {
            try {
                channel.shutdown().awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }

    public boolean isRunning() {
        return running.get() && watchThread.isAlive();
    }
}
