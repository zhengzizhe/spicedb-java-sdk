package com.authx.sdk.policy;

import com.authx.sdk.exception.AuthxAuthException;
import com.authx.sdk.exception.AuthxConnectionException;
import com.authx.sdk.exception.AuthxInvalidArgumentException;
import com.authx.sdk.exception.AuthxPreconditionException;
import com.authx.sdk.exception.AuthxResourceExhaustedException;
import com.authx.sdk.exception.AuthxTimeoutException;
import com.authx.sdk.exception.AuthxUnimplementedException;
import com.authx.sdk.exception.CircuitBreakerOpenException;
import com.authx.sdk.exception.InvalidPermissionException;
import com.authx.sdk.exception.InvalidRelationException;
import com.authx.sdk.exception.InvalidResourceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SR:C4 — {@link RetryPolicy#defaults()} must classify every known-permanent
 * SDK exception as non-retryable, and {@link RetryPolicy#isPermanent} must
 * agree with {@link RetryPolicy#shouldRetry} on those classes.
 */
class RetryPolicyPermanentTest {

    @Test
    void defaults_do_not_retry_permanent_exceptions() {
        com.authx.sdk.policy.RetryPolicy policy = RetryPolicy.defaults();
        assertFalse(policy.shouldRetry(new AuthxAuthException("x", null)));
        assertFalse(policy.shouldRetry(new AuthxInvalidArgumentException("x", null)));
        assertFalse(policy.shouldRetry(new AuthxUnimplementedException("x", null)));
        assertFalse(policy.shouldRetry(new AuthxPreconditionException("x", null)));
        assertFalse(policy.shouldRetry(new AuthxResourceExhaustedException("x", null)));
        assertFalse(policy.shouldRetry(new CircuitBreakerOpenException("x")));
        // SR:C4 — the three schema-validation exceptions were previously NOT
        // on the deny list even though they're permanent.
        assertFalse(policy.shouldRetry(new InvalidPermissionException("x")));
        assertFalse(policy.shouldRetry(new InvalidRelationException("x")));
        assertFalse(policy.shouldRetry(new InvalidResourceException("x")));
    }

    @Test
    void defaults_still_retry_transient_exceptions() {
        com.authx.sdk.policy.RetryPolicy policy = RetryPolicy.defaults();
        assertTrue(policy.shouldRetry(new AuthxConnectionException("net hiccup", null)));
        assertTrue(policy.shouldRetry(new AuthxTimeoutException("deadline", null)));
    }

    @Test
    void isPermanent_agrees_with_shouldRetry_complement() {
        com.authx.sdk.policy.RetryPolicy policy = RetryPolicy.defaults();
        // For every deny-listed class, isPermanent is true and shouldRetry is false.
        assertTrue(policy.isPermanent(new AuthxAuthException("x", null)));
        assertTrue(policy.isPermanent(new InvalidPermissionException("x")));
        // For transient errors, isPermanent is false.
        assertFalse(policy.isPermanent(new AuthxConnectionException("x", null)));
    }

    @Test
    void isPermanent_returns_false_for_non_Exception_Throwable() {
        com.authx.sdk.policy.RetryPolicy policy = RetryPolicy.defaults();
        // Errors (OOM, StackOverflow) are not Exception — retry-pipeline
        // decisions don't apply. Contract is documented by returning false.
        assertFalse(policy.isPermanent(new OutOfMemoryError()));
    }
}
