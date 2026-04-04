package com.authcses.sdk.transport;

import com.authcses.sdk.event.DefaultTypedEventBus;
import com.authcses.sdk.event.SdkTypedEvent;
import com.authcses.sdk.event.TypedEventBus;
import com.authcses.sdk.exception.CircuitBreakerOpenException;
import com.authcses.sdk.model.*;
import com.authcses.sdk.model.enums.Permissionship;
import com.authcses.sdk.policy.CircuitBreakerPolicy;
import com.authcses.sdk.policy.PolicyRegistry;
import com.authcses.sdk.policy.RetryPolicy;
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
import java.util.function.Supplier;

/**
 * Resilience transport: per-resource-type circuit breaker + retry via Resilience4j.
 * Replaces CircuitBreakerTransport + PolicyAwareRetryTransport.
 */
public class ResilientTransport extends ForwardingTransport {

    private static final System.Logger LOG = System.getLogger(ResilientTransport.class.getName());

    private final SdkTransport delegate;
    private final PolicyRegistry policyRegistry;
    private final TypedEventBus eventBus;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Retry> retries = new ConcurrentHashMap<>();

    public ResilientTransport(SdkTransport delegate, PolicyRegistry policyRegistry, TypedEventBus eventBus) {
        this.delegate = delegate;
        this.policyRegistry = policyRegistry;
        this.eventBus = eventBus != null ? eventBus : new DefaultTypedEventBus();
    }

    @Override
    protected SdkTransport delegate() {
        return delegate;
    }

    @Override
    public CheckResult check(CheckRequest request) {
        String resourceType = request.resource().type();
        var policy = policyRegistry.resolve(resourceType);
        Set<String> failOpenPerms = policy.getCircuitBreaker() != null
                ? policy.getCircuitBreaker().getFailOpenPermissions() : Set.of();

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
    public List<String> lookupSubjects(LookupSubjectsRequest request, Consistency consistency) {
        return executeWithResilience(request.resource().type(),
                () -> delegate.lookupSubjects(request, consistency));
    }

    @Override
    public List<String> lookupResources(LookupResourcesRequest request, Consistency consistency) {
        return executeWithResilience(request.resourceType(),
                () -> delegate.lookupResources(request, consistency));
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
        var breaker = breakers.get(resourceType);
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
        CircuitBreaker breaker = resolveBreaker(resourceType);
        Retry retry = resolveRetry(resourceType);

        try {
            Supplier<T> withCircuitBreaker = CircuitBreaker.decorateSupplier(breaker, call);
            Supplier<T> decorated = Retry.decorateSupplier(retry, withCircuitBreaker);
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw new CircuitBreakerOpenException("Circuit breaker is OPEN for " + resourceType, e);
        }
    }

    private CircuitBreaker resolveBreaker(String resourceType) {
        return breakers.computeIfAbsent(resourceType, this::createBreaker);
    }

    private Retry resolveRetry(String resourceType) {
        return retries.computeIfAbsent(resourceType, this::createRetry);
    }

    private CircuitBreaker createBreaker(String resourceType) {
        var policy = policyRegistry.resolve(resourceType).getCircuitBreaker();
        if (policy == null) policy = CircuitBreakerPolicy.defaults();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold((float) policy.getFailureRateThreshold())
                .slowCallRateThreshold((float) policy.getSlowCallRateThreshold())
                .slowCallDurationThreshold(policy.getSlowCallDuration())
                .slidingWindowType(policy.getSlidingWindowType() == CircuitBreakerPolicy.SlidingWindowType.TIME_BASED
                        ? CircuitBreakerConfig.SlidingWindowType.TIME_BASED
                        : CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(policy.getSlidingWindowSize())
                .minimumNumberOfCalls(policy.getMinimumNumberOfCalls())
                .waitDurationInOpenState(policy.getWaitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(policy.getPermittedCallsInHalfOpen())
                .build();

        CircuitBreaker breaker = CircuitBreaker.of("authcses-" + resourceType, config);

        if (!policy.isEnabled()) {
            breaker.transitionToDisabledState();
        }

        // Bridge events to TypedEventBus
        breaker.getEventPublisher().onStateTransition(event -> {
            var transition = event.getStateTransition();
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
                LOG.log(System.Logger.Level.INFO, "Circuit breaker [{0}]: {1}", resourceType, transition);
                eventBus.publish(sdkEvent);
            }
        });

        return breaker;
    }

    private Retry createRetry(String resourceType) {
        var policy = policyRegistry.resolve(resourceType).getRetry();
        if (policy == null || policy.getMaxAttempts() <= 0) {
            return Retry.of("authcses-" + resourceType + "-noop",
                    RetryConfig.custom().maxAttempts(1).build());
        }

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(policy.getMaxAttempts())
                .intervalFunction(io.github.resilience4j.core.IntervalFunction.ofExponentialRandomBackoff(
                        policy.getBaseDelay().toMillis(), policy.getMultiplier(), policy.getJitterFactor()))
                .retryOnException(t -> t instanceof Exception e && policy.shouldRetry(e))
                .build();

        Retry retry = Retry.of("authcses-" + resourceType, config);

        retry.getEventPublisher().onRetry(event ->
                LOG.log(System.Logger.Level.WARNING, "Retry {0}/{1} for [{2}]: {3}",
                        event.getNumberOfRetryAttempts(), policy.getMaxAttempts(),
                        resourceType, event.getLastThrowable().getMessage()));

        return retry;
    }
}
