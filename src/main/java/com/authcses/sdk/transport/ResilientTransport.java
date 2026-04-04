package com.authcses.sdk.transport;

import com.authcses.sdk.event.SdkEvent;
import com.authcses.sdk.event.SdkEventBus;
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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Resilience transport: per-resource-type circuit breaker + retry via Resilience4j.
 * Replaces CircuitBreakerTransport + PolicyAwareRetryTransport.
 */
public class ResilientTransport implements SdkTransport {

    private static final System.Logger LOG = System.getLogger(ResilientTransport.class.getName());

    private final SdkTransport delegate;
    private final PolicyRegistry policyRegistry;
    private final SdkEventBus eventBus;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Retry> retries = new ConcurrentHashMap<>();

    public ResilientTransport(SdkTransport delegate, PolicyRegistry policyRegistry, SdkEventBus eventBus) {
        this.delegate = delegate;
        this.policyRegistry = policyRegistry;
        this.eventBus = eventBus != null ? eventBus : new SdkEventBus();
    }

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency) {
        var policy = policyRegistry.resolve(resourceType);
        Set<String> failOpenPerms = policy.getCircuitBreaker() != null
                ? policy.getCircuitBreaker().getFailOpenPermissions() : Set.of();

        try {
            return executeWithResilience(resourceType,
                    () -> delegate.check(resourceType, resourceId, permission, subjectType, subjectId, consistency));
        } catch (CircuitBreakerOpenException e) {
            if (failOpenPerms.contains(permission)) {
                return new CheckResult(Permissionship.HAS_PERMISSION, null, Optional.empty());
            }
            throw e;
        }
    }

    @Override
    public BulkCheckResult checkBulk(String resourceType, String resourceId,
                                     String permission, List<String> subjectIds, String defaultSubjectType,
                                     Consistency consistency) {
        return executeWithResilience(resourceType,
                () -> delegate.checkBulk(resourceType, resourceId, permission, subjectIds, defaultSubjectType, consistency));
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        String resourceType = updates.isEmpty() ? "" : updates.getFirst().resourceType();
        return executeWithResilience(resourceType, () -> delegate.writeRelationships(updates));
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        String resourceType = updates.isEmpty() ? "" : updates.getFirst().resourceType();
        return executeWithResilience(resourceType, () -> delegate.deleteRelationships(updates));
    }

    @Override
    public List<Tuple> readRelationships(String resourceType, String resourceId,
                                          String relation, Consistency consistency) {
        return executeWithResilience(resourceType,
                () -> delegate.readRelationships(resourceType, resourceId, relation, consistency));
    }

    @Override
    public List<String> lookupSubjects(String resourceType, String resourceId,
                                        String permission, String subjectType,
                                        Consistency consistency) {
        return executeWithResilience(resourceType,
                () -> delegate.lookupSubjects(resourceType, resourceId, permission, subjectType, consistency));
    }

    @Override
    public List<String> lookupResources(String resourceType, String permission,
                                         String subjectType, String subjectId,
                                         Consistency consistency) {
        return executeWithResilience(resourceType,
                () -> delegate.lookupResources(resourceType, permission, subjectType, subjectId, consistency));
    }

    @Override
    public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
        if (items.isEmpty()) return List.of();
        String resourceType = items.getFirst().resourceType();
        return executeWithResilience(resourceType, () -> delegate.checkBulkMulti(items, consistency));
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

        // Bridge events to SdkEventBus
        breaker.getEventPublisher().onStateTransition(event -> {
            var transition = event.getStateTransition();
            SdkEvent sdkEvent = switch (transition) {
                case CLOSED_TO_OPEN, HALF_OPEN_TO_OPEN, CLOSED_TO_FORCED_OPEN ->
                        SdkEvent.CIRCUIT_OPENED;
                case OPEN_TO_HALF_OPEN, FORCED_OPEN_TO_HALF_OPEN ->
                        SdkEvent.CIRCUIT_HALF_OPENED;
                case HALF_OPEN_TO_CLOSED, FORCED_OPEN_TO_CLOSED ->
                        SdkEvent.CIRCUIT_CLOSED;
                default -> null;
            };
            if (sdkEvent != null) {
                LOG.log(System.Logger.Level.INFO, "Circuit breaker [{0}]: {1}", resourceType, transition);
                eventBus.fire(sdkEvent, resourceType + ": " + transition);
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
