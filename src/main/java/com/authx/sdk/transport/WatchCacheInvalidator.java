package com.authx.sdk.transport;

import com.authx.sdk.cache.Cache;
import com.authx.sdk.cache.IndexedCache;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.RelationshipChange;
import com.authzed.api.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
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
    private final SdkMetrics metrics;
    private final ManagedChannel channel;
    private final boolean ownsChannel;
    private final Metadata authMetadata;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<String> lastToken = new AtomicReference<>(null);
    private final Thread watchThread;
    private final List<Consumer<RelationshipChange>> listeners = new CopyOnWriteArrayList<>();
    private final LongAdder droppedListenerEvents = new LongAdder();
    private final ExecutorService listenerExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(10_000),
            r -> { Thread t = new Thread(r, "authx-sdk-watch-dispatch"); t.setDaemon(true); return t; },
            (r, executor) -> droppedListenerEvents.increment());

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
    public WatchCacheInvalidator(ManagedChannel channel, String presharedKey,
                                  Cache<CheckKey, CheckResult> cache, SdkMetrics metrics) {
        this.cache = cache;
        this.metrics = metrics;
        this.channel = channel;
        this.ownsChannel = false;

        this.authMetadata = new Metadata();
        authMetadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + presharedKey);

        this.watchThread = new Thread(this::watchLoop, "authx-sdk-watch");
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
     * @deprecated Use {@link #WatchCacheInvalidator(ManagedChannel, String, Cache, SdkMetrics)} instead.
     */
    @Deprecated
    public WatchCacheInvalidator(List<String> endpoints, String presharedKey, boolean useTls,
                                  Cache<CheckKey, CheckResult> cache) {
        this.cache = cache;
        this.metrics = new SdkMetrics();

        String target = endpoints.getFirst();
        var builder = ManagedChannelBuilder.forTarget(target);
        if (!useTls) builder.usePlaintext();
        this.channel = builder.build();
        this.ownsChannel = true;

        this.authMetadata = new Metadata();
        authMetadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + presharedKey);

        this.watchThread = new Thread(this::watchLoop, "authx-sdk-watch");
        this.watchThread.setDaemon(true);
        // Thread not started here — caller must call start()
    }

    private static final int MAX_FAILURES_NEVER_CONNECTED = 3;
    private static final int MAX_FAILURES_AFTER_CONNECTED = 20;

    private void watchLoop() {
        long backoffMs = 1000;
        int consecutiveFailures = 0;
        boolean everConnected = false;
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

                // Don't reset here — stub.watch() is lazy.
                // Reset only after first successful stream.hasNext().
                boolean connected = false;

                while (stream.hasNext() && running.get()) {
                    if (!connected) {
                        connected = true;
                        everConnected = true;
                        LOG.log(System.Logger.Level.INFO, "Watch stream connected");
                        backoffMs = 1000;
                        consecutiveFailures = 0;
                    }
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

                // Check for permanent errors (UNIMPLEMENTED, UNAUTHENTICATED, PERMISSION_DENIED)
                if (isPermanentError(e)) {
                    String reason = getPermanentErrorReason(e);
                    LOG.log(System.Logger.Level.WARNING,
                            "Watch stopped: {0}. Cache invalidation will rely on TTL expiration only.", reason);
                    running.set(false);
                    return;
                }

                consecutiveFailures++;
                int maxFailures = everConnected
                        ? MAX_FAILURES_AFTER_CONNECTED : MAX_FAILURES_NEVER_CONNECTED;
                if (consecutiveFailures >= maxFailures) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Watch stopped after {0} consecutive failures ({1}). " +
                            "Cache invalidation will rely on TTL expiration only. Last error: {2}",
                            consecutiveFailures,
                            everConnected ? "server went away" : "never connected — check target address",
                            e.getMessage());
                    running.set(false);
                    return;
                }

                metrics.recordWatchReconnect();
                // Log at WARNING on 1st attempt, then at power-of-2 intervals to avoid spam
                boolean shouldLog = consecutiveFailures == 1
                        || (consecutiveFailures & (consecutiveFailures - 1)) == 0;
                if (shouldLog) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Watch stream disconnected ({0}/{1}), reconnecting in {2}ms: {3}",
                            consecutiveFailures, maxFailures, backoffMs, e.getMessage());
                }
                try {
                    long jitter = ThreadLocalRandom.current().nextLong(backoffMs / 4 + 1);
                    Thread.sleep(backoffMs + jitter);
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

    /** Number of listener dispatch events dropped because the dispatch queue was full. */
    public long droppedListenerEvents() { return droppedListenerEvents.sum(); }

    public boolean isRunning() {
        return running.get() && watchThread.isAlive();
    }

    private static boolean isPermanentError(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof io.grpc.StatusRuntimeException sre) {
                var code = sre.getStatus().getCode();
                if (code == io.grpc.Status.Code.UNIMPLEMENTED
                        || code == io.grpc.Status.Code.UNAUTHENTICATED
                        || code == io.grpc.Status.Code.PERMISSION_DENIED) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static String getPermanentErrorReason(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof io.grpc.StatusRuntimeException sre) {
                return switch (sre.getStatus().getCode()) {
                    case UNIMPLEMENTED -> "Watch not supported by this SpiceDB backend (use PostgreSQL or CockroachDB 3+ nodes)";
                    case UNAUTHENTICATED -> "authentication failed (check preshared key)";
                    case PERMISSION_DENIED -> "permission denied (check SpiceDB RBAC configuration)";
                    default -> sre.getStatus().toString();
                };
            }
            cause = cause.getCause();
        }
        return e.getMessage();
    }
}
