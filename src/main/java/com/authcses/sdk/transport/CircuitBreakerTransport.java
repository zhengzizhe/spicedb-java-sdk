package com.authcses.sdk.transport;

import com.authcses.sdk.circuit.CircuitBreaker;
import com.authcses.sdk.exception.CircuitBreakerOpenException;
import com.authcses.sdk.model.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Wraps a SdkTransport with circuit breaker protection.
 * When open, check() on whitelisted permissions returns HAS_PERMISSION (fail-open).
 * All other operations throw CircuitBreakerOpenException.
 */
public class CircuitBreakerTransport implements SdkTransport {

    private final SdkTransport delegate;
    private final CircuitBreaker breaker;
    private final Set<String> failOpenPermissions;

    public CircuitBreakerTransport(SdkTransport delegate, CircuitBreaker breaker, Set<String> failOpenPermissions) {
        this.delegate = delegate;
        this.breaker = breaker;
        this.failOpenPermissions = failOpenPermissions != null ? failOpenPermissions : Set.of();
    }

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency) {
        try {
            return breaker.execute(() ->
                    delegate.check(resourceType, resourceId, permission, subjectType, subjectId, consistency));
        } catch (CircuitBreakerOpenException e) {
            if (failOpenPermissions.contains(permission)) {
                return new CheckResult(com.authcses.sdk.model.enums.Permissionship.HAS_PERMISSION, null, Optional.empty());
            }
            throw e;
        }
    }

    @Override
    public BulkCheckResult checkBulk(String resourceType, String resourceId,
                                     String permission, List<String> subjectIds, String defaultSubjectType,
                                     Consistency consistency) {
        return breaker.execute(() ->
                delegate.checkBulk(resourceType, resourceId, permission, subjectIds, defaultSubjectType, consistency));
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        return breaker.execute(() -> delegate.writeRelationships(updates));
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        return breaker.execute(() -> delegate.deleteRelationships(updates));
    }

    @Override
    public List<Tuple> readRelationships(String resourceType, String resourceId,
                                          String relation, Consistency consistency) {
        return breaker.execute(() ->
                delegate.readRelationships(resourceType, resourceId, relation, consistency));
    }

    @Override
    public List<String> lookupSubjects(String resourceType, String resourceId,
                                        String permission, String subjectType,
                                        Consistency consistency) {
        return breaker.execute(() ->
                delegate.lookupSubjects(resourceType, resourceId, permission, subjectType, consistency));
    }

    @Override
    public List<String> lookupResources(String resourceType, String permission,
                                         String subjectType, String subjectId,
                                         Consistency consistency) {
        return breaker.execute(() ->
                delegate.lookupResources(resourceType, permission, subjectType, subjectId, consistency));
    }

    @Override
    public void close() {
        delegate.close();
    }

    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }
}
