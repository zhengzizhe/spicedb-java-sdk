package com.authx.sdk.transport;

import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.event.SdkTypedEvent;
import com.authx.sdk.event.TypedEventBus;
import com.authx.sdk.exception.AuthxAuthException;
import com.authx.sdk.exception.AuthxInvalidArgumentException;
import com.authx.sdk.exception.AuthxPreconditionException;
import com.authx.sdk.exception.AuthxResourceExhaustedException;
import com.authx.sdk.exception.AuthxUnimplementedException;
import com.authx.sdk.exception.CircuitBreakerOpenException;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
import com.authx.sdk.policy.CircuitBreakerPolicy;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ResourcePolicy;
import com.authx.sdk.policy.RetryPolicy;
import com.authx.sdk.trace.LogCtx;
import com.authx.sdk.trace.LogFields;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Resilience transport: per-resource-type circuit breaker + retry via Resilience4j.
 * Replaces CircuitBreakerTransport + PolicyAwareRetryTransport.
 */
public class ResilientTransport extends ForwardingTransport {

    private static final System.Logger LOG = System.getLogger(ResilientTransport.class.getName());
    private static final int MAX_INSTANCES = 1000;

    private final SdkTransport delegate;
    private final PolicyRegistry policyRegistry;
    private final TypedEventBus eventBus;
    /**
     * Per-resource-type circuit breakers. Backed by Caffeine's W-TinyLFU
     * eviction (true LRU + frequency tracking) — replaces the old
     * ConcurrentHashMap+random-eviction scheme that could evict an OPEN
     * breaker for a still-failing resource type, resetting protection.
     *
     * <p>W-TinyLFU keeps high-traffic types (whether failing or succeeding)
     * and naturally evicts one-shot/low-traffic types, so a SaaS workload
     * with 100 active tenants and 1000 dormant-tenant resource types stays
     * correctly protected.
     */
    private final ConcurrentMap<String, CircuitBreaker> breakers;
    /**
     * Per-resource-type Retry instances. Same eviction semantics as
     * {@link #breakers}; the removal listener on the breakers cache also
     * evicts the corresponding retry to keep the two maps in sync.
     */
    private final ConcurrentMap<String, Retry> retries;
    private final CircuitBreaker defaultBreaker;
    private final Retry defaultRetry;

    // Retry budget: sliding 1-second window, max 20% retry rate
    private final LongAdder retryCount = new LongAdder();
    private final LongAdder requestCount = new LongAdder();
    private final AtomicLong lastResetTime = new AtomicLong(System.nanoTime());

    private final SdkMetrics sdkMetrics;

    public ResilientTransport(SdkTransport delegate, PolicyRegistry policyRegistry,
                              TypedEventBus eventBus, SdkMetrics sdkMetrics) {
        this.delegate = delegate;
        this.policyRegistry = policyRegistry;
        this.eventBus = eventBus != null ? eventBus : new DefaultTypedEventBus();
        this.sdkMetrics = sdkMetrics;
        // Caffeine-backed concurrent maps: W-TinyLFU eviction (true LRU + frequency)
        // bounded at MAX_INSTANCES. The retries cache is the source of truth for
        // sync — the breakers removal listener evicts the matching retry entry
        // so the two maps never drift.
        this.retries = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(MAX_INSTANCES)
                .<String, Retry>build()
                .asMap();
        this.breakers = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(MAX_INSTANCES)
                .<String, CircuitBreaker>removalListener((key, value, cause) -> {
                    if (cause.wasEvicted() && value != null) {
                        value.reset();
                        if (key != null) retries.remove(key);
                    }
                })
                .<String, CircuitBreaker>build()
                .asMap();
        this.defaultBreaker = createBreaker("__default__");
        this.defaultRetry = createRetry("__default__");
    }

    public ResilientTransport(SdkTransport delegate, PolicyRegistry policyRegistry, TypedEventBus eventBus) {
        this(delegate, policyRegistry, eventBus, null);
    }

    @Override
    protected SdkTransport delegate() {
        return delegate;
    }

    @Override
    public CheckResult check(CheckRequest request) {
        String resourceType = request.resource().type();
        ResourcePolicy policy = policyRegistry.resolve(resourceType);
        Set<String> failOpenPerms = policy.circuitBreaker() != null
                ? policy.circuitBreaker().failOpenPermissions() : Set.of();

        try {
            return executeWithResilience(resourceType,
                    () -> delegate.check(request));
        } catch (CircuitBreakerOpenException e) {
            if (failOpenPerms.contains(request.permission().name())) {
                return new CheckResult(Permissionship.HAS_PERMISSION, null, Optional.empty());
            }
            throw e;
        }
    }

    @Override
    public BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects) {
        return executeWithResilience(request.resource().type(),
                () -> delegate.checkBulk(request, subjects));
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        String resourceType = updates.isEmpty() ? "" : updates.getFirst().resource().type();
        return executeWithResilience(resourceType, () -> delegate.writeRelationships(updates));
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        String resourceType = updates.isEmpty() ? "" : updates.getFirst().resource().type();
        return executeWithResilience(resourceType, () -> delegate.deleteRelationships(updates));
    }

    @Override
    public List<Tuple> readRelationships(ResourceRef resource, Relation relation, Consistency consistency) {
        return executeWithResilience(resource.type(),
                () -> delegate.readRelationships(resource, relation, consistency));
    }

    @Override
    public List<SubjectRef> lookupSubjects(LookupSubjectsRequest request) {
        return executeWithResilience(request.resource().type(),
                () -> delegate.lookupSubjects(request));
    }

    @Override
    public List<ResourceRef> lookupResources(LookupResourcesRequest request) {
        return executeWithResilience(request.resourceType(),
                () -> delegate.lookupResources(request));
    }

    @Override
    public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
        if (items.isEmpty()) return List.of();
        String resourceType = items.getFirst().resource().type();
        return executeWithResilience(resourceType, () -> delegate.checkBulkMulti(items, consistency));
    }

    @Override
    public RevokeResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                        Relation optionalRelation) {
        return executeWithResilience(resource.type(),
                () -> delegate.deleteByFilter(resource, subject, optionalRelation));
    }

    @Override
    public ExpandTree expand(ResourceRef resource, Permission permission, Consistency consistency) {
        return executeWithResilience(resource.type(),
                () -> delegate.expand(resource, permission, consistency));
    }

    public io.github.resilience4j.circuitbreaker.CircuitBreaker.State getCircuitBreakerState(String resourceType) {
        io.github.resilience4j.circuitbreaker.CircuitBreaker breaker = breakers.get(resourceType);
        return breaker != null ? breaker.getState() : io.github.resilience4j.circuitbreaker.CircuitBreaker.State.DISABLED;
    }

    @Override
    public void close() {
        breakers.values().forEach(CircuitBreaker::reset);
        breakers.clear();
        retries.clear();
        delegate.close();
    }

    // ---- Internal ----

    private <T> T executeWithResilience(String resourceType, Supplier<T> call) {
        requestCount.increment();
        long startNanos = System.nanoTime();
        boolean isError = true;

        CircuitBreaker breaker = resolveBreaker(resourceType);
        Retry retry = resolveRetry(resourceType);

        try {
            // Order: CB wraps Retry wraps call.
            // Retry failures are internal to CB — one request = one CB record.
            Supplier<T> withRetry = Retry.decorateSupplier(retry, call);
            Supplier<T> decorated = CircuitBreaker.decorateSupplier(breaker, withRetry);
            T result = decorated.get();
            isError = false;
            return result;
        } catch (CallNotPermittedException e) {
            throw new CircuitBreakerOpenException("Circuit breaker is OPEN for " + resourceType, e);
        } finally {
            if (sdkMetrics != null) {
                long micros = (System.nanoTime() - startNanos) / 1_000;
                sdkMetrics.recordRequest(micros, isError);
            }
        }
    }

    private CircuitBreaker resolveBreaker(String resourceType) {
        // Caffeine-backed map: W-TinyLFU handles eviction automatically when
        // size exceeds MAX_INSTANCES, with proper LRU + frequency tracking.
        // The removal listener reset()s the evicted breaker and drops the
        // matching retry entry so the two maps stay in sync.
        return breakers.computeIfAbsent(resourceType, this::createBreaker);
    }

    private Retry resolveRetry(String resourceType) {
        return retries.computeIfAbsent(resourceType, this::createRetry);
    }

    private CircuitBreaker createBreaker(String resourceType) {
        CircuitBreakerPolicy policy = policyRegistry.resolve(resourceType).circuitBreaker();
        if (policy == null) policy = CircuitBreakerPolicy.defaults();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold((float) policy.failureRateThreshold())
                .slowCallRateThreshold((float) policy.slowCallRateThreshold())
                .slowCallDurationThreshold(policy.slowCallDuration())
                .slidingWindowType(policy.slidingWindowType() == CircuitBreakerPolicy.SlidingWindowType.TIME_BASED
                        ? CircuitBreakerConfig.SlidingWindowType.TIME_BASED
                        : CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(policy.slidingWindowSize())
                .minimumNumberOfCalls(policy.minimumNumberOfCalls())
                .waitDurationInOpenState(policy.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(policy.permittedCallsInHalfOpen())
                .ignoreExceptions(
                        AuthxInvalidArgumentException.class,
                        AuthxAuthException.class,
                        AuthxResourceExhaustedException.class,
                        AuthxUnimplementedException.class,
                        AuthxPreconditionException.class)
                .build();

        CircuitBreaker breaker = CircuitBreaker.of("authx-" + resourceType, config);

        if (!policy.enabled()) {
            breaker.transitionToDisabledState();
        }

        // Bridge events to TypedEventBus
        breaker.getEventPublisher().onStateTransition(event -> {
            io.github.resilience4j.circuitbreaker.CircuitBreaker.StateTransition transition = event.getStateTransition();
            SdkTypedEvent sdkEvent = switch (transition) {
                case CLOSED_TO_OPEN, HALF_OPEN_TO_OPEN, CLOSED_TO_FORCED_OPEN ->
                        new SdkTypedEvent.CircuitOpened(Instant.now(), resourceType, null);
                case OPEN_TO_HALF_OPEN, FORCED_OPEN_TO_HALF_OPEN ->
                        new SdkTypedEvent.CircuitHalfOpened(Instant.now(), resourceType);
                case HALF_OPEN_TO_CLOSED, FORCED_OPEN_TO_CLOSED ->
                        new SdkTypedEvent.CircuitClosed(Instant.now(), resourceType);
                default -> null;
            };
            if (sdkEvent != null) {
                LOG.log(System.Logger.Level.INFO, LogCtx.fmt("Circuit breaker [{0}]: {1}", resourceType, transition));
                eventBus.publish(sdkEvent);
            }
        });

        return breaker;
    }

    /**
     * Sliding 1-second window retry budget. Returns true if retries are within budget (< 20% of requests).
     */
    private boolean checkRetryBudget() {
        long now = System.nanoTime();
        long last = lastResetTime.get();
        if (now - last > 1_000_000_000L && lastResetTime.compareAndSet(last, now)) {
            // Only one thread resets per window
            retryCount.reset();
            requestCount.reset();
        }
        long retries = retryCount.sum();
        long requests = requestCount.sum();
        // Allow retries freely when sample size is small (< 25 requests);
        // enforce 20% budget only under sustained load
        return requests < 25 || (double) retries / requests < 0.2;
    }

    private Retry createRetry(String resourceType) {
        RetryPolicy policy = policyRegistry.resolve(resourceType).retry();
        if (policy == null || policy.maxAttempts() <= 0) {
            return Retry.of("authx-" + resourceType + "-noop",
                    RetryConfig.custom().maxAttempts(1).build());
        }

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(policy.maxAttempts())
                .intervalFunction(io.github.resilience4j.core.IntervalFunction.ofExponentialRandomBackoff(
                        policy.baseDelay().toMillis(), policy.multiplier(), policy.jitterFactor()))
                .retryOnException(t -> {
                    if (!(t instanceof Exception e) || !policy.shouldRetry(e)) {
                        return false;
                    }
                    if (!checkRetryBudget()) {
                        LOG.log(System.Logger.Level.WARNING, LogCtx.fmt(
                                "Retry budget exhausted for [{0}], skipping retry"
                                        + LogFields.suffix(resourceType, null, null, null),
                                resourceType));
                        return false;
                    }
                    retryCount.increment();
                    return true;
                })
                .build();

        Retry retry = Retry.of("authx-" + resourceType, config);

        retry.getEventPublisher().onRetry(event -> {
            // SR:req-8 — enrich current OTel span with retry attempt + event
            try {
                io.opentelemetry.api.trace.Span span = io.opentelemetry.api.trace.Span.current();
                span.setAttribute("authx.retry.attempt", event.getNumberOfRetryAttempts());
                span.setAttribute("authx.retry.max", policy.maxAttempts());
                span.addEvent("retry_attempt",
                        io.opentelemetry.api.common.Attributes.of(
                                io.opentelemetry.api.common.AttributeKey.longKey("attempt"),
                                (long) event.getNumberOfRetryAttempts(),
                                io.opentelemetry.api.common.AttributeKey.stringKey("errorType"),
                                event.getLastThrowable() == null ? "null"
                                        : event.getLastThrowable().getClass().getSimpleName()));
            } catch (Throwable ignored) {
                /* span enrichment is best-effort */
            }
            // SR:req-10 — retry is normal product of resilience policy, not
            // operator-actionable; downgrade WARN → DEBUG. Real signal ("retry
            // budget exhausted") stays at WARN above.
            LOG.log(System.Logger.Level.DEBUG, LogCtx.fmt(
                    "Retry {0}/{1} for [{2}]: {3}",
                    event.getNumberOfRetryAttempts(), policy.maxAttempts(),
                    resourceType, event.getLastThrowable().getMessage()));
        });

        return retry;
    }
}
