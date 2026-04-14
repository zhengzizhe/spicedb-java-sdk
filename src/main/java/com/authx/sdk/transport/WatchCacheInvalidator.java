package com.authx.sdk.transport;

import com.authx.sdk.cache.Cache;
import com.authx.sdk.cache.IndexedCache;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.RelationshipChange;
import com.authx.sdk.spi.DuplicateDetector;
import com.authzed.api.v1.*;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
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
    private final Metadata authMetadata;
    private final AtomicBoolean running = new AtomicBoolean(true);
    /**
     * Latched true on the first successful {@link #start()} call.
     * Prevents a (theoretical) double-start race where two callers both
     * observe {@code watchThread.getState() == NEW} and both attempt to
     * {@code .start()} the thread — the second would throw
     * {@link IllegalThreadStateException}. In practice {@code start()} is
     * only invoked from {@code AuthxClientBuilder.buildWatch()} which runs
     * once, but defense in depth is cheap here.
     */
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<String> lastToken = new AtomicReference<>(null);
    private final AtomicReference<WatchConnectionState> state =
            new AtomicReference<>(WatchConnectionState.NOT_STARTED);
    /** Current live session — tracked so {@link #close()} can cancel its gRPC call promptly. */
    private final AtomicReference<WatchStreamSession> currentSession = new AtomicReference<>();
    private final Thread watchThread;
    private final List<Consumer<RelationshipChange>> listeners = new CopyOnWriteArrayList<>();
    /**
     * Local dedup gate keyed on {@code zedToken}. Used to suppress listener
     * dispatch for events that SpiceDB replayed after a stream reconnect
     * (cursor boundary replays). Cache invalidation intentionally bypasses
     * this gate — invalidating the same key twice is harmless and we never
     * want to leave a stale entry in place because we dropped an event as
     * "a duplicate".
     */
    private final DuplicateDetector<String> dedup;
    private final LongAdder droppedListenerEvents = new LongAdder();
    /**
     * Executor used to invoke user listeners. May be either:
     *
     * <ul>
     *   <li>A user-supplied {@link ExecutorService} (via
     *       {@code SdkComponents.builder().watchListenerExecutor(...)}), in
     *       which case {@link #ownsListenerExecutor} is false and we leave its
     *       lifecycle to the user, OR</li>
     *   <li>The default executor created in {@link #defaultListenerExecutor()}:
     *       single-threaded, bounded queue (10 000), drop-on-full with the
     *       drop counted in {@link #droppedListenerEvents}.</li>
     * </ul>
     */
    private final ExecutorService listenerExecutor;
    private final boolean ownsListenerExecutor;

    /**
     * Optional event bus for publishing watch lifecycle events
     * (e.g. {@code WatchCursorExpired}). Set via {@link #setEventBus} after
     * construction by the builder. Null = events not published.
     */
    private volatile com.authx.sdk.event.TypedEventBus eventBus;

    /** Allow the builder to wire an event bus after construction. */
    public void setEventBus(com.authx.sdk.event.TypedEventBus bus) {
        this.eventBus = bus;
    }

    /** Register a listener for relationship changes. */
    public void addListener(Consumer<RelationshipChange> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<RelationshipChange> listener) {
        listeners.remove(listener);
    }

    /**
     * Create with a shared channel (preferred — reuses the main client's gRPC channel),
     * a no-op dedup detector and the default listener executor.
     * Backward-compatible overload.
     */
    public WatchCacheInvalidator(ManagedChannel channel, String presharedKey,
                                  Cache<CheckKey, CheckResult> cache, SdkMetrics metrics) {
        this(channel, presharedKey, cache, metrics, DuplicateDetector.noop(), null);
    }

    /**
     * Create with a shared channel + custom {@link DuplicateDetector}, using the
     * default listener executor. Backward-compatible overload.
     */
    public WatchCacheInvalidator(ManagedChannel channel, String presharedKey,
                                  Cache<CheckKey, CheckResult> cache, SdkMetrics metrics,
                                  DuplicateDetector<String> dedup) {
        this(channel, presharedKey, cache, metrics, dedup, null);
    }

    /**
     * Full-control constructor — supply your own dedup detector AND listener executor.
     *
     * @param listenerExecutor optional executor for invoking user listeners. When
     *                         {@code null} the SDK creates and owns a default
     *                         single-threaded bounded executor (drop-on-full).
     *                         When non-null the SDK does NOT shut it down on
     *                         {@link #close()} — the caller owns its lifecycle.
     */
    public WatchCacheInvalidator(ManagedChannel channel, String presharedKey,
                                  Cache<CheckKey, CheckResult> cache, SdkMetrics metrics,
                                  DuplicateDetector<String> dedup,
                                  ExecutorService listenerExecutor) {
        this.cache = cache;
        this.metrics = metrics;
        this.channel = channel;
        this.dedup = dedup != null ? dedup : DuplicateDetector.noop();
        if (listenerExecutor != null) {
            this.listenerExecutor = listenerExecutor;
            this.ownsListenerExecutor = false;
        } else {
            this.listenerExecutor = defaultListenerExecutor();
            this.ownsListenerExecutor = true;
        }

        this.authMetadata = new Metadata();
        authMetadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + presharedKey);

        this.watchThread = new Thread(this::watchLoop, "authx-sdk-watch");
        this.watchThread.setDaemon(true);
        // Thread not started here — caller must call start()
    }

    /**
     * Construct the default listener executor: 1 thread, bounded queue (10k),
     * drop-on-full policy where drops increment {@link #droppedListenerEvents}.
     *
     * <p>The single thread guarantees in-order delivery to listeners — listeners
     * see events in the order SpiceDB delivered them. If you need parallel
     * dispatch, supply your own multi-threaded executor (which loses ordering).
     */
    private ExecutorService defaultListenerExecutor() {
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(10_000),
                r -> { Thread t = new Thread(r, "authx-sdk-watch-dispatch"); t.setDaemon(true); return t; },
                (r, executor) -> droppedListenerEvents.increment());
    }

    /**
     * Start the watch stream. Must be called after construction. Idempotent —
     * only the first call takes effect; subsequent calls are no-ops. Also a
     * no-op if {@link #close()} has already run (we don't resurrect a
     * closed invalidator).
     */
    public void start() {
        if (!running.get()) {
            // close() already ran — don't start a thread we immediately have
            // to shut down. This also prevents the observable state from
            // briefly showing CONNECTING for a session that will never exist.
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;  // someone else already started us
        }
        state.set(WatchConnectionState.CONNECTING);
        watchThread.start();
    }

    /**
     * Observable Watch lifecycle state. See {@link WatchConnectionState} for
     * the state machine. Updated lock-free from the watch thread and the gRPC
     * executor thread — callers see eventually-consistent reads.
     */
    public WatchConnectionState state() {
        return state.get();
    }

    private static final int MAX_FAILURES_NEVER_CONNECTED = 3;
    private static final int MAX_FAILURES_AFTER_CONNECTED = 20;
    /**
     * Drain-loop poll timeout. Chosen to balance:
     * <ul>
     *   <li><b>State transition latency</b>: an external observer of {@link #state()}
     *       sees transitions no later than this interval after they become visible
     *       to the watch thread.</li>
     *   <li><b>Idle CPU cost</b>: on a quiet stream the watch thread wakes once per
     *       interval to re-check the connection predicates. 100ms = 10Hz idle wake,
     *       negligible on a modern CPU.</li>
     * </ul>
     */
    private static final Duration SESSION_POLL_INTERVAL = Duration.ofMillis(100);
    /**
     * Threshold for "rapid" cursor-expiry recurrences. If the cursor expires this
     * many times without an intervening successful data receive, we start applying
     * a small backoff to avoid a tight reset-resubscribe loop when the SpiceDB
     * {@code --datastore-gc-window} is misconfigured (e.g. set to 1s) or the watch
     * thread is genuinely unable to keep up.
     */
    private static final int CURSOR_EXPIRY_BACKOFF_THRESHOLD = 3;

    /**
     * Default threshold for application-layer stall detection. SpiceDB sends
     * checkpoints every few seconds even on idle streams, so silence longer
     * than this almost always means SpiceDB is stuck (deadlocked datastore
     * replica, internal bug) or a middlebox dropped the stream while keeping
     * TCP alive. Forces a reconnect via {@link WatchStreamSession#close()}.
     *
     * <p>Set generously vs. SpiceDB's checkpoint cadence so healthy streams
     * never trigger false positives. Override via
     * {@link #setStaleStreamThreshold(Duration)} if your SpiceDB has an
     * unusually long quantization window.
     */
    private static final Duration DEFAULT_STALE_STREAM_THRESHOLD = Duration.ofSeconds(60);

    private volatile Duration staleStreamThreshold = DEFAULT_STALE_STREAM_THRESHOLD;
    /**
     * Wall-clock instant of the last received message (data or checkpoint).
     * Updated on every WatchResponse — see {@link #processResponse}. Read in
     * the watch loop to detect application-layer stalls.
     */
    private volatile java.time.Instant lastMessageAt = java.time.Instant.now();

    /** Override the application-layer stall threshold. Must be &gt; 0. */
    public void setStaleStreamThreshold(Duration threshold) {
        if (threshold == null || threshold.isZero() || threshold.isNegative()) {
            throw new IllegalArgumentException("staleStreamThreshold must be positive");
        }
        this.staleStreamThreshold = threshold;
    }

    private void watchLoop() {
        // Outer try/finally guarantees the state field always transitions to
        // STOPPED when the watch thread exits — even if the catch block itself
        // throws (e.g. a misbehaving logger or metrics sink). Without this,
        // observers of state() could be permanently stuck on RECONNECTING.
        try {
            watchLoopBody();
        } finally {
            state.set(WatchConnectionState.STOPPED);
        }
    }

    private void watchLoopBody() {
        long backoffMs = 1000;
        int consecutiveFailures = 0;
        int consecutiveCursorExpiries = 0;
        boolean everConnected = false;
        while (running.get()) {
            WatchStreamSession session = null;
            try {
                var requestBuilder = WatchRequest.newBuilder();
                String token = lastToken.get();
                if (token != null) {
                    requestBuilder.setOptionalStartCursor(
                            ZedToken.newBuilder().setToken(token).build());
                }

                // Open a new session (low-level ClientCall wired with HEADERS + MESSAGE + CLOSE callbacks).
                session = new WatchStreamSession(channel, authMetadata, requestBuilder.build());
                currentSession.set(session);
                session.start();
                // Reset the staleness clock for the new session — a previous
                // session's silence shouldn't trigger a stall on the new one.
                lastMessageAt = java.time.Instant.now();

                // Drain events from the session's queue until it closes.
                //
                // CONNECTED detection: a Watch stream is considered "connected"
                // when EITHER of two signals fires:
                //
                //   (a) ClientCall onHeaders → SpiceDB sent us HTTP/2 HEADERS or
                //       data in response to the Watch request (definitive proof
                //       the call was accepted at the application layer)
                //
                //   (b) channel.getState() == READY → the underlying gRPC channel
                //       is healthy and transport-layer-ready. Because gRPC Java
                //       (and SpiceDB) typically send response HEADERS lazily
                //       (only on the first onNext()), signal (a) may not fire
                //       for minutes on a pure-read SpiceDB with no writes.
                //       Channel state gives us a fast, correct fallback.
                //
                // Whichever signal fires first wins. Prior to this change, we
                // relied solely on the first data message (via blocking
                // Iterator.hasNext()), which produced the famous "watchReconnects=0"
                // observability bug — see bug B1 in the benchmark report.
                //
                // Invariant: queued messages are drained BEFORE observing session closure.
                // Polling first (with a short timeout) and only breaking on (poll==null && isClosed)
                // avoids a race where onMessage and onClose both fire on the gRPC thread
                // between loop iterations — otherwise the pending message would be lost
                // and we'd reconnect without a valid cursor.
                while (running.get()) {
                    // ─── State + log unified on the same CONNECTED condition ───
                    //
                    // Connection has TWO observable signals:
                    //   1. session.isConnected() — gRPC HEADERS received (definitive
                    //      proof the Watch endpoint accepted our call)
                    //   2. isChannelReady() — underlying channel is READY (transport
                    //      is healthy; server hasn't necessarily responded yet)
                    //
                    // The state field tracks "observable CONNECTED" — either signal
                    // is enough. Logs MUST follow the same condition so operators
                    // see "Watch stream connected" exactly when state.get() returns
                    // CONNECTED, with no silent gap.
                    //
                    // Retry budget (everConnected) uses the STRICTER signal — only
                    // session.isConnected() — because a healthy channel doesn't prove
                    // the Watch endpoint specifically is accepting calls (wrong auth,
                    // unsupported datastore, etc. would still leave the channel READY).
                    boolean observablyConnected = session.isConnected() || isChannelReady();
                    if (observablyConnected && state.get() != WatchConnectionState.CONNECTED) {
                        state.set(WatchConnectionState.CONNECTED);
                        if (!everConnected) {
                            LOG.log(System.Logger.Level.INFO, "Watch stream connected");
                        } else if (consecutiveFailures > 0) {
                            LOG.log(System.Logger.Level.INFO, "Watch stream reconnected");
                        }
                    }
                    // Reset retry budget only on the strict signal — see comment above.
                    if (session.isConnected() && !everConnected) {
                        everConnected = true;
                        backoffMs = 1000;
                        consecutiveFailures = 0;
                    } else if (session.isConnected() && consecutiveFailures > 0) {
                        backoffMs = 1000;
                        consecutiveFailures = 0;
                    }

                    WatchResponse response = session.poll(SESSION_POLL_INTERVAL);
                    if (response != null) {
                        processResponse(response);
                        // Successful data receive clears the cursor-expiry counter —
                        // we've made real progress past the stale region.
                        consecutiveCursorExpiries = 0;
                        continue;  // keep draining
                    }
                    // poll() returned null (timeout). Only NOW check for session closure —
                    // if we'd checked before polling we could drop a concurrently-delivered
                    // message that arrived just before onClose fired.
                    if (session.isClosed()) break;

                    // Application-layer stall detection (Kafka max.poll.interval.ms /
                    // etcd WithProgressNotify pattern). lastMessageAt was reset to
                    // "now" when the session opened, so we won't false-trigger before
                    // the first message — the threshold has to elapse with no traffic.
                    {
                        Duration idle = Duration.between(lastMessageAt, java.time.Instant.now());
                        if (idle.compareTo(staleStreamThreshold) > 0) {
                            LOG.log(System.Logger.Level.WARNING,
                                    "Watch stream stalled (no message for {0}s, threshold={1}s). " +
                                            "Forcing reconnect — SpiceDB may be deadlocked or middlebox " +
                                            "dropped the stream.",
                                    idle.toSeconds(), staleStreamThreshold.toSeconds());
                            var bus = eventBus;
                            if (bus != null) {
                                try {
                                    bus.publish(new com.authx.sdk.event.SdkTypedEvent.WatchStreamStale(
                                            java.time.Instant.now(), idle, staleStreamThreshold));
                                } catch (Exception pubEx) {
                                    LOG.log(System.Logger.Level.WARNING,
                                            "WatchStreamStale event publish failed: {0}", pubEx.getMessage());
                                }
                            }
                            // Cancel the underlying gRPC call → triggers onClose →
                            // the outer loop's exception/retry path takes over.
                            session.close();
                            // Reset the clock so the next session starts fresh.
                            lastMessageAt = java.time.Instant.now();
                            break;
                        }
                    }
                }

                // Session ended. If it was an error, re-throw to hit the retry handler.
                Status terminalStatus = session.terminalStatus();
                if (terminalStatus != null && !terminalStatus.isOk()) {
                    throw terminalStatus.asRuntimeException();
                }
            } catch (InterruptedException ie) {
                // Interrupt is our canonical "stop now" signal — close() sets
                // running=false then interrupts the thread. Restore the flag
                // for any downstream code that might check it, and exit. The
                // outer try/finally will publish STOPPED.
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // NOTE: state.set(STOPPED) on early returns is handled by the
                // outer watchLoop() try/finally — don't duplicate it here, so
                // that an exception in the catch body can't leave state stale.
                if (!running.get()) {
                    return;
                }

                // Check for permanent errors (UNIMPLEMENTED, UNAUTHENTICATED, PERMISSION_DENIED)
                if (isPermanentError(e)) {
                    String reason = getPermanentErrorReason(e);
                    LOG.log(System.Logger.Level.WARNING,
                            "Watch stopped: {0}. Cache invalidation will rely on TTL expiration only.", reason);
                    running.set(false);
                    return;
                }

                // Detect "cursor too old" — SpiceDB returns an error when the
                // optional_start_cursor we sent points to a revision that has
                // already been garbage-collected (--datastore-gc-window has
                // elapsed since that revision was created).
                //
                // This is recoverable: we just need to drop the stale cursor
                // and resubscribe from HEAD. We do NOT count it against the
                // retry budget because it's a "we held a stale token" issue,
                // not a "SpiceDB is sick" issue — UNLESS it keeps recurring,
                // in which case something is genuinely wrong (mis-tuned GC
                // window, thread starvation, ...) and we must not tight-loop.
                if (lastToken.get() != null && isCursorExpired(e)) {
                    consecutiveCursorExpiries++;
                    String expiredToken = lastToken.get();

                    // K8s informer / etcd / Debezium pattern: when the cursor
                    // is too old, all events between the last cursor and now
                    // are LOST. Cache entries written before the disconnect may
                    // now reflect state that was overwritten during the gap, so
                    // we cannot trust any of them. Full invalidation is the
                    // only correct response — losing cache warmth is far
                    // cheaper than serving wrong permission decisions.
                    if (cache != null) {
                        cache.invalidateAll();
                    }

                    // Publish event so business code can alert / audit / wait
                    // for re-warming. Subscribers do their own thing; we just
                    // make the data-loss window observable.
                    var bus = eventBus;
                    if (bus != null) {
                        try {
                            bus.publish(new com.authx.sdk.event.SdkTypedEvent.WatchCursorExpired(
                                    java.time.Instant.now(), expiredToken, consecutiveCursorExpiries));
                        } catch (Exception pubEx) {
                            // Don't let a bad subscriber kill the watch loop.
                            LOG.log(System.Logger.Level.WARNING,
                                    "WatchCursorExpired event publish failed: {0}", pubEx.getMessage());
                        }
                    }

                    LOG.log(System.Logger.Level.WARNING,
                            "Watch cursor expired (likely SpiceDB gc-window elapsed during disconnect). " +
                                    "Cache fully invalidated. Resubscribing from HEAD. " +
                                    "Events between the last cursor and now are LOST. " +
                                    "Recurrence count: {0}. Last error: {1}",
                            consecutiveCursorExpiries, e.getMessage());
                    lastToken.set(null);
                    // Observability: we ARE reconnecting (building a new session
                    // with a fresh cursor). Reflect that in state() immediately,
                    // otherwise external observers see a stale CONNECTED reading
                    // left over from the now-dead session until the next session
                    // establishes.
                    state.set(WatchConnectionState.RECONNECTING);
                    // Rapid recurrence → apply a bounded backoff. Starts kicking
                    // in after the third consecutive expiry without intervening
                    // successful data; caps at 5s so we're never unresponsive to
                    // shutdown for too long.
                    if (consecutiveCursorExpiries >= CURSOR_EXPIRY_BACKOFF_THRESHOLD) {
                        long sleepMs = Math.min(
                                500L * (1L << Math.min(
                                        consecutiveCursorExpiries - CURSOR_EXPIRY_BACKOFF_THRESHOLD, 4)),
                                5_000L);
                        try {
                            Thread.sleep(sleepMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    continue;
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
                state.set(WatchConnectionState.RECONNECTING);
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
                    return;  // STOPPED state set by outer finally
                }
            } finally {
                if (session != null) {
                    try { session.close(); } catch (Exception ignored) {}
                    currentSession.compareAndSet(session, null);
                }
            }
        }
        // STOPPED state set by the outer watchLoop() try/finally.
    }

    /**
     * Process a single {@link WatchResponse}: update cursor, invalidate cache,
     * optionally dispatch listeners (gated by the dedup detector).
     *
     * <p>Extracted from the watchLoop for readability; the body is unchanged
     * from the previous inline version except that it's now called from the
     * session-based drain loop.
     */
    private void processResponse(WatchResponse response) {
        // Application-layer liveness: any message (data or checkpoint) proves
        // SpiceDB is producing — refresh the staleness clock.
        lastMessageAt = java.time.Instant.now();
        // Track token for reconnection (always, even on checkpoints)
        if (response.hasChangesThrough()) {
            lastToken.set(response.getChangesThrough().getToken());
        }

        // Checkpoints carry no updates — just heartbeats to advance the cursor.
        if (response.getIsCheckpoint()) {
            return;
        }

        String changeToken = response.hasChangesThrough()
                ? response.getChangesThrough().getToken() : null;

        // Transaction metadata is attached to the *response* (the whole SpiceDB
        // transaction), so all updates in this response share the same map.
        Map<String, String> txMetadata = response.hasOptionalTransactionMetadata()
                ? flattenStruct(response.getOptionalTransactionMetadata())
                : Map.of();

        // Listener-dispatch dedup gate. Invariant: cache invalidation ALWAYS
        // runs (every pod must clear its own cache, even if a response is a
        // cursor-replay duplicate). Only listener side effects are gated.
        //
        // Defensive wrapping: a misbehaving user-supplied DuplicateDetector
        // MUST NOT break the watch stream. If tryProcess() throws, we fail OPEN
        // (dispatch anyway). Fail-closed would cause silent event loss, which
        // is worse than a duplicate. Fail-throw would propagate up through the
        // gRPC listener and trigger a reconnect loop that keeps re-hitting the
        // same broken detector (see F5-1 review finding).
        boolean dispatchListeners = !listeners.isEmpty()
                && (changeToken == null || tryDedupSafely(changeToken));

        for (var update : response.getUpdatesList()) {
            var rel = update.getRelationship();
            String resourceType = rel.getResource().getObjectType();
            String resourceId = rel.getResource().getObjectId();

            // 1. Invalidate cache (never gated — idempotent and required)
            String indexKey = resourceType + ":" + resourceId;
            if (cache instanceof IndexedCache<CheckKey, CheckResult> indexed) {
                indexed.invalidateByIndex(indexKey);
            } else {
                cache.invalidateAll(key -> indexKey.equals(key.resourceIndex()));
            }

            // 2. Notify user listeners (async, gated by dedup check above)
            if (dispatchListeners) {
                var op = mapOperation(update.getOperation());
                String subRel = rel.getSubject().getOptionalRelation();
                // Normalize empty-string caveat name to null — protobuf's default
                // string is "", so hasOptionalCaveat() can be true even when the
                // writer didn't actually set a name. Callers expect null-or-value.
                String caveatName = null;
                if (rel.hasOptionalCaveat()) {
                    String n = rel.getOptionalCaveat().getCaveatName();
                    if (!n.isEmpty()) caveatName = n;
                }
                Instant expiresAt = rel.hasOptionalExpiresAt()
                        ? timestampToInstant(rel.getOptionalExpiresAt())
                        : null;
                var change = new RelationshipChange(
                        op, resourceType, resourceId, rel.getRelation(),
                        rel.getSubject().getObject().getObjectType(),
                        rel.getSubject().getObject().getObjectId(),
                        subRel.isEmpty() ? null : subRel, changeToken,
                        caveatName, expiresAt, txMetadata);
                dispatchChangeSafely(change);
            }
        }
    }

    /**
     * Submit a {@link RelationshipChange} to the listener executor with full
     * exception isolation.
     *
     * <p>The listener executor is either the SDK-owned bounded executor
     * (rejection handler increments {@link #droppedListenerEvents}) or a
     * user-supplied {@link ExecutorService}. A user-supplied executor may:
     *
     * <ul>
     *   <li>Throw {@link java.util.concurrent.RejectedExecutionException} if
     *       its queue is full or it has been shut down</li>
     *   <li>Throw arbitrary runtime exceptions from a custom rejection handler</li>
     * </ul>
     *
     * <p>Letting either escape would bubble all the way up to the Watch drain
     * loop and be treated as a stream failure — triggering a reconnect storm
     * and (worse) skipping the cache invalidation of subsequent updates in
     * the same response batch, because the {@code for (update : updates)} loop
     * would terminate mid-way.
     *
     * <p>Policy: on any throw, count the drop in {@link #droppedListenerEvents}
     * (so it surfaces in the same observability channel as SDK-internal drops)
     * and swallow — the cache invalidation that ran BEFORE this call stands,
     * and the next updates in the batch are processed normally.
     */
    private void dispatchChangeSafely(RelationshipChange change) {
        try {
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
        } catch (Throwable t) {
            droppedListenerEvents.increment();
            LOG.log(System.Logger.Level.WARNING,
                    "Watch listener executor rejected dispatch ({0}); counted as dropped. "
                            + "Cache invalidation for the current batch is unaffected.",
                    t.toString());
        }
    }

    @Override
    public void close() {
        running.set(false);
        // Cancel any in-flight gRPC call so the watch thread exits poll() promptly
        var session = currentSession.getAndSet(null);
        if (session != null) {
            try { session.close(); } catch (Exception ignored) {}
        }
        // Only interrupt/join if the thread was actually started. If start()
        // was never called (watchThread still in NEW state) interrupt() is a
        // cheap no-op and join() returns immediately — but we skip both for
        // clarity.
        if (started.get()) {
            watchThread.interrupt();
            try {
                watchThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (watchThread.isAlive()) {
                // Join timed out. Do NOT forcibly transition state to STOPPED
                // here (F11-5): the thread is still alive and its own
                // try/finally will set STOPPED when it eventually exits.
                // Publishing STOPPED prematurely would lie to observers about
                // thread liveness. Log the leak so operators can see it.
                LOG.log(System.Logger.Level.WARNING,
                        "Watch thread did not exit within 3s join budget; leaving state as {0}. " +
                                "Thread will transition to STOPPED once it exits on its own.",
                        state.get());
            }
        } else {
            // Never started — mark STOPPED so observers don't see NOT_STARTED
            // after close() returns (which would suggest "call start() next").
            state.set(WatchConnectionState.STOPPED);
        }
        // Only shut down the listener executor if we created it. User-supplied
        // executors stay alive — the user owns their lifecycle (matches the
        // ownership model used for Netty/gRPC executors elsewhere in the SDK).
        if (ownsListenerExecutor) {
            listenerExecutor.shutdown();
            try {
                if (!listenerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    listenerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                listenerExecutor.shutdownNow();
            }
        }
        // Note: we never own the channel — it's passed in by AuthxClientBuilder
        // and lifecycle-managed by SdkInfrastructure. Shutting it down here would
        // be a layering violation (other transports share the same channel).
    }

    /** Number of listener dispatch events dropped because the dispatch queue was full. */
    public long droppedListenerEvents() { return droppedListenerEvents.sum(); }

    public boolean isRunning() {
        return running.get() && watchThread.isAlive();
    }

    /**
     * Call {@link DuplicateDetector#tryProcess(Object)} with full exception isolation.
     * A user-supplied detector is untrusted code running on the gRPC executor path —
     * if it throws (Caffeine OOM, Redis timeout, buggy custom impl), we MUST NOT let
     * the exception propagate into the ClientCall listener, because gRPC will treat
     * that as a stream failure and trigger a reconnect that keeps re-hitting the
     * same broken detector (a hot reconnect loop).
     *
     * <p>On any {@link Throwable}, we log and fail OPEN (return true). Fail-open is
     * safer than fail-closed here because the dedup gate is a de-duplication
     * optimization, not a correctness boundary — dispatching a duplicate event is
     * strictly better than silently dropping a real one.
     */
    private boolean tryDedupSafely(String changeToken) {
        try {
            return dedup.tryProcess(changeToken);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    "DuplicateDetector.tryProcess threw — dispatching event anyway (fail-open). Error: {0}",
                    t.toString());
            return true;
        }
    }

    /**
     * Package-private accessor for the wired {@link DuplicateDetector}.
     * Intended for test verification that the {@code AuthxClientBuilder} →
     * {@code SdkComponents} → {@code WatchCacheInvalidator} wiring correctly
     * propagates a user-supplied detector. Not part of the public API.
     */
    DuplicateDetector<String> dedupDetector() {
        return dedup;
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

    /**
     * Heuristically detect "your start cursor is too old" errors from SpiceDB.
     *
     * <p>The SpiceDB v1 Watch proto says: "if this cursor references a point-in-time
     * containing data that has been garbage collected, an error will be returned"
     * — but doesn't specify the gRPC status code, and we don't want to hard-code
     * a single code in case the SpiceDB implementation changes.
     *
     * <p>The detection requires BOTH:
     * <ol>
     *   <li>A status code commonly used for "not retryable in current state":
     *       {@code FAILED_PRECONDITION}, {@code OUT_OF_RANGE}, {@code INVALID_ARGUMENT}</li>
     *   <li>A message hint mentioning revision/cursor/garbage collection</li>
     * </ol>
     *
     * <p>The combined check minimizes false positives — a generic
     * {@code FAILED_PRECONDITION} on a different feature won't trigger cursor
     * reset (which would lose the user's progress).
     */
    private static boolean isCursorExpired(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof StatusRuntimeException sre) {
                Status.Code code = sre.getStatus().getCode();
                if (code != Status.Code.FAILED_PRECONDITION
                        && code != Status.Code.OUT_OF_RANGE
                        && code != Status.Code.INVALID_ARGUMENT) {
                    return false;
                }
                String desc = sre.getStatus().getDescription();
                if (desc == null) return false;
                String low = desc.toLowerCase();
                // Match SpiceDB phrasing variants — these substrings appear in
                // "specified start cursor is too old", "revision out of range",
                // "garbage collected", etc.
                return low.contains("cursor")
                        || low.contains("revision")
                        || low.contains("garbage")
                        || low.contains("gc window")
                        || low.contains("too old");
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

    /**
     * Check whether the underlying gRPC channel is {@code READY}. Used as a
     * non-blocking fallback signal for "Watch is connected" when the server
     * hasn't yet sent us the first response HEADERS frame. Returns false if
     * the channel is null (e.g. in-memory clients).
     */
    private boolean isChannelReady() {
        if (channel == null) return false;
        // getState(false) does NOT trigger a reconnect attempt — we just observe.
        return channel.getState(false) == io.grpc.ConnectivityState.READY;
    }

    private static RelationshipChange.Operation mapOperation(RelationshipUpdate.Operation op) {
        return switch (op) {
            case OPERATION_CREATE -> RelationshipChange.Operation.CREATE;
            case OPERATION_DELETE -> RelationshipChange.Operation.DELETE;
            // OPERATION_TOUCH, OPERATION_UNSPECIFIED, UNRECOGNIZED → TOUCH (idempotent default)
            default -> RelationshipChange.Operation.TOUCH;
        };
    }

    private static Instant timestampToInstant(Timestamp ts) {
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    /**
     * Flatten a protobuf {@link Struct} into a {@code Map<String,String>} for audit use.
     * Top-level keys are preserved; scalar values (string/number/bool/null) are stringified;
     * nested struct/list values fall back to the protobuf text representation so no data is lost.
     */
    private static Map<String, String> flattenStruct(Struct struct) {
        if (struct.getFieldsCount() == 0) return Map.of();
        var out = new HashMap<String, String>(struct.getFieldsCount());
        for (var entry : struct.getFieldsMap().entrySet()) {
            String val = stringifyValue(entry.getValue());
            // Skip NULL_VALUE / KIND_NOT_SET entries entirely — putting the
            // literal string "null" in an audit map confuses downstream consumers
            // (they can't distinguish "absent" from "writer explicitly set null").
            // Omitting the key gives the cleaner "absent" semantics.
            if (val != null) out.put(entry.getKey(), val);
        }
        return out;
    }

    private static String stringifyValue(Value v) {
        return switch (v.getKindCase()) {
            case STRING_VALUE -> v.getStringValue();
            case NUMBER_VALUE -> {
                double d = v.getNumberValue();
                // Prefer integer form only when the value is a whole number AND
                // STRICTLY within [-2^63, 2^63) — note the half-open upper bound.
                //
                // Why strict: (double) Long.MAX_VALUE rounds UP to 2^63 because
                // Long.MAX_VALUE (= 2^63 - 1) cannot be represented exactly in
                // IEEE-754 double. So a check like `d <= (double) Long.MAX_VALUE`
                // admits d == 2^63, which then casts to a saturated Long.MAX_VALUE
                // and silently misreports as "9223372036854775807" — off by one
                // and indistinguishable from a correct answer. Excluding the
                // boundary forces 2^63 into the double form instead, which is
                // lossy but honest.
                //
                // 0x1p63 is the literal double value 2^63 = 9.223372036854776e18.
                // (double) Long.MIN_VALUE = -2^63 is exact, so the lower bound
                // can stay inclusive.
                boolean isWhole = Double.isFinite(d) && d == Math.floor(d);
                boolean inLongRange = d >= (double) Long.MIN_VALUE && d < 0x1p63;
                yield (isWhole && inLongRange)
                        ? Long.toString((long) d)
                        : Double.toString(d);
            }
            case BOOL_VALUE -> Boolean.toString(v.getBoolValue());
            // NULL_VALUE / KIND_NOT_SET → return null so flattenStruct skips the entry.
            case NULL_VALUE, KIND_NOT_SET -> null;
            case STRUCT_VALUE, LIST_VALUE -> v.toString().trim(); // protobuf text format
        };
    }

    // ────────────────────────────────────────────────────────────────────
    //  WatchStreamSession — encapsulates ONE gRPC server-streaming call
    // ────────────────────────────────────────────────────────────────────

    /**
     * A single-use Watch gRPC session built on top of {@link ClientCall} rather
     * than the blocking iterator stub. This gives us:
     *
     * <ul>
     *   <li><b>Accurate connection detection</b> via {@code onHeaders} — the
     *       session is marked {@link #isConnected() connected} the moment the
     *       HTTP/2 HEADERS frame returns, not when the first data message
     *       arrives. Fixes the pre-existing bug where a pure-read SpiceDB
     *       would never report "connected".</li>
     *   <li><b>Prompt cancellation</b> via {@link ClientCall#cancel(String, Throwable)}
     *       — {@code close()} interrupts an active call immediately, so SDK
     *       shutdown doesn't wait for the next Watch event.</li>
     *   <li><b>Consumer-driven backpressure</b> — the bounded queue plus
     *       consumer-side {@link ClientCall#request(int) request()} means that
     *       a slow watch thread naturally pushes back on SpiceDB instead of
     *       letting the queue grow unbounded.</li>
     *   <li><b>Clean separation of concerns</b> — this class owns one gRPC call;
     *       the parent {@code WatchCacheInvalidator} owns the reconnect loop,
     *       metrics, and listener dispatch.</li>
     * </ul>
     *
     * <p>Thread-safety: {@code start}/{@code close}/{@code poll} are called from
     * the watch thread. The gRPC listener callbacks (onHeaders/onMessage/onClose)
     * run on the gRPC executor. Cross-thread state is coordinated via atomics
     * and a blocking queue. {@code call.request(int)} is called exclusively
     * from the watch thread (sequential, never concurrent).
     */
    private static final class WatchStreamSession implements AutoCloseable {

        /**
         * Bounded queue capacity. Small enough to bound memory in the presence
         * of event storms, large enough to absorb scheduling jitter between the
         * gRPC executor thread (producer) and the watch thread (consumer).
         *
         * <p>With the consumer-driven backpressure policy (only the watch thread
         * calls {@code request(1)}), the queue should normally hold 0-1 items.
         * The buffer just smooths over brief GC pauses and context switches.
         */
        private static final int QUEUE_CAPACITY = 32;

        private final ManagedChannel channel;
        private final Metadata authMetadata;
        private final WatchRequest request;

        // Bounded to enforce backpressure; see QUEUE_CAPACITY Javadoc.
        private final BlockingQueue<WatchResponse> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private final AtomicReference<Status> terminalStatus = new AtomicReference<>();
        private final CountDownLatch sessionEnded = new CountDownLatch(1);
        private volatile ClientCall<WatchRequest, WatchResponse> call;

        WatchStreamSession(ManagedChannel channel, Metadata authMetadata, WatchRequest request) {
            this.channel = channel;
            this.authMetadata = authMetadata;
            this.request = request;
        }

        /**
         * Open the gRPC call and start streaming. Must be called exactly once.
         * Does not block — returns as soon as the call is initiated; the
         * session becomes {@link #isConnected() connected} asynchronously
         * when gRPC HEADERS are received.
         */
        void start() {
            this.call = channel.newCall(WatchServiceGrpc.getWatchMethod(), CallOptions.DEFAULT);
            call.start(new ClientCall.Listener<WatchResponse>() {
                @Override
                public void onHeaders(Metadata headers) {
                    // Truly connected: HTTP/2 HEADERS frame received, stream is usable.
                    connected.set(true);
                }

                @Override
                public void onMessage(WatchResponse message) {
                    // Non-blocking offer: we're on the gRPC executor thread and
                    // MUST NOT block — a blocking put() would starve other calls
                    // sharing the executor. If the queue is full, drop this
                    // delivery attempt on the floor; the consumer (watch thread)
                    // will not have called request(1) for it yet because it's
                    // still processing earlier items, so gRPC flow control will
                    // stall the server naturally. See onMessage flow note in
                    // #poll below.
                    //
                    // NOTE: crucially, we do NOT call call.request(1) here.
                    // That responsibility moved to the watch thread (see poll())
                    // so that demand is driven by what the consumer has actually
                    // processed, not what the transport has delivered.
                    if (!queue.offer(message)) {
                        LOG.log(System.Logger.Level.WARNING,
                                "Watch queue full (capacity={0}); dropping a delivery — "
                                        + "consumer is slower than server event rate.",
                                QUEUE_CAPACITY);
                    }
                }

                @Override
                public void onClose(Status status, Metadata trailers) {
                    if (!status.isOk()) {
                        terminalStatus.set(status);
                    }
                    sessionEnded.countDown();
                }

                @Override
                public void onReady() {
                    // No-op for server-streaming: we're the receiver, not the sender.
                }
            }, authMetadata);

            // Send the single request and half-close our side of the stream.
            call.sendMessage(request);
            call.halfClose();
            // Prime flow control by asking for the INITIAL batch of messages.
            // We pre-request a small number so the server can start sending
            // immediately; the watch thread will top up the window as it drains.
            call.request(QUEUE_CAPACITY);
        }

        /**
         * Block up to {@code timeout} for the next response. Returns {@code null}
         * on timeout.
         *
         * <p><b>Flow control</b>: after successfully taking a message, this method
         * calls {@link ClientCall#request(int) call.request(1)} to tell gRPC the
         * consumer is ready for one more. This is the ONLY place the request
         * window is refilled once the session is running, which means:
         *
         * <ul>
         *   <li>gRPC never has more outstanding delivery credit than the watch
         *       thread has explicitly granted</li>
         *   <li>If the watch thread is slow, the credit runs out and gRPC stops
         *       delivering — the server sees HTTP/2 window exhaustion and stalls
         *       naturally</li>
         *   <li>No unbounded queue growth possible under any consumer speed</li>
         * </ul>
         *
         * <p>Called only from the watch thread, so the single-threaded contract
         * required by {@link ClientCall#request(int)} is satisfied without a lock.
         */
        WatchResponse poll(Duration timeout) throws InterruptedException {
            WatchResponse response = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response != null && call != null) {
                // Refill the one credit we just consumed. Safe because this method
                // only runs on the watch thread and is strictly serialized with
                // itself — no concurrent callers of call.request().
                try {
                    call.request(1);
                } catch (IllegalStateException alreadyClosed) {
                    // Call was cancelled or closed between our poll and refill.
                    // Harmless — we already have the response, and the next poll
                    // will see session.isClosed() and exit cleanly.
                }
            }
            return response;
        }

        /** True once gRPC HEADERS have returned (≈ HTTP/2 stream fully established). */
        boolean isConnected() {
            return connected.get();
        }

        /** True once the stream has ended (cleanly or with error). */
        boolean isClosed() {
            return sessionEnded.getCount() == 0;
        }

        /** Terminal gRPC status if the stream ended abnormally; {@code null} otherwise. */
        Status terminalStatus() {
            return terminalStatus.get();
        }

        /** Cancel the underlying gRPC call. Idempotent. */
        @Override
        public void close() {
            if (call != null) {
                try {
                    call.cancel("Watch session closed by SDK", null);
                } catch (Exception ignored) {
                    // Cancellation races are expected during shutdown — swallow.
                }
            }
        }
    }
}
