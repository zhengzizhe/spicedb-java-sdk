package com.authx.sdk.policy;

import com.authx.sdk.exception.AuthxAuthException;
import com.authx.sdk.exception.AuthxConnectionException;
import com.authx.sdk.exception.CircuitBreakerOpenException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class PolicyAdditionalTest {

    // ---- CachePolicy ----
    @Nested class CachePolicyTest {
        @Test void defaultBuilder() {
            var p = CachePolicy.builder().build();
            assertThat(p.enabled()).isTrue();
            assertThat(p.ttl()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.maxIdleTime()).isNull();
        }

        @Test void disabled() {
            var p = CachePolicy.disabled();
            assertThat(p.enabled()).isFalse();
        }

        @Test void ofTtl() {
            var p = CachePolicy.of(Duration.ofSeconds(10));
            assertThat(p.enabled()).isTrue();
            assertThat(p.ttl()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test void perPermissionTtl() {
            var p = CachePolicy.builder()
                .ttl(Duration.ofSeconds(5))
                .forPermission("view", Duration.ofSeconds(10))
                .forPermission("delete", Duration.ofMillis(500))
                .build();
            assertThat(p.resolveTtl("view")).isEqualTo(Duration.ofSeconds(10));
            assertThat(p.resolveTtl("delete")).isEqualTo(Duration.ofMillis(500));
            assertThat(p.resolveTtl("edit")).isEqualTo(Duration.ofSeconds(5)); // falls back to default
        }

        @Test void maxIdleTime() {
            var p = CachePolicy.builder().maxIdleTime(Duration.ofMinutes(1)).build();
            assertThat(p.maxIdleTime()).isEqualTo(Duration.ofMinutes(1));
        }
    }

    // ---- CircuitBreakerPolicy ----
    @Nested class CircuitBreakerPolicyTest {
        @Test void defaults() {
            var p = CircuitBreakerPolicy.defaults();
            assertThat(p.enabled()).isTrue();
            assertThat(p.failureRateThreshold()).isEqualTo(50.0);
            assertThat(p.slowCallRateThreshold()).isEqualTo(80.0);
            assertThat(p.slowCallDuration()).isEqualTo(Duration.ofMillis(500));
            assertThat(p.slidingWindowType()).isEqualTo(CircuitBreakerPolicy.SlidingWindowType.COUNT_BASED);
            assertThat(p.slidingWindowSize()).isEqualTo(100);
            assertThat(p.minimumNumberOfCalls()).isEqualTo(10);
            assertThat(p.waitInOpenState()).isEqualTo(Duration.ofSeconds(30));
            assertThat(p.permittedCallsInHalfOpen()).isEqualTo(5);
            assertThat(p.failOpenPermissions()).isEmpty();
        }

        @Test void disabled() {
            var p = CircuitBreakerPolicy.disabled();
            assertThat(p.enabled()).isFalse();
        }

        @Test void customBuilder() {
            var stateRef = new AtomicReference<String>();
            var p = CircuitBreakerPolicy.builder()
                .failureRateThreshold(75.0)
                .slidingWindowType(CircuitBreakerPolicy.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(60)
                .failOpenPermissions(Set.of("view"))
                .onStateChange((from, to) -> stateRef.set(from + "->" + to))
                .build();

            assertThat(p.failureRateThreshold()).isEqualTo(75.0);
            assertThat(p.slidingWindowType()).isEqualTo(CircuitBreakerPolicy.SlidingWindowType.TIME_BASED);
            assertThat(p.slidingWindowSize()).isEqualTo(60);
            assertThat(p.failOpenPermissions()).containsExactly("view");

            p.onStateChange().accept("CLOSED", "OPEN");
            assertThat(stateRef.get()).isEqualTo("CLOSED->OPEN");
        }

        @Test void failOpenPermissionsIsImmutableCopy() {
            var mutable = new java.util.HashSet<>(Set.of("view"));
            var p = CircuitBreakerPolicy.builder().failOpenPermissions(mutable).build();
            mutable.add("edit");
            assertThat(p.failOpenPermissions()).doesNotContain("edit");
        }
    }

    // ---- RetryPolicy ----
    @Nested class RetryPolicyTest {
        @Test void defaults() {
            var p = RetryPolicy.defaults();
            assertThat(p.maxAttempts()).isEqualTo(3);
            assertThat(p.baseDelay()).isEqualTo(Duration.ofMillis(50));
            assertThat(p.maxDelay()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.multiplier()).isEqualTo(2.0);
            assertThat(p.jitterFactor()).isEqualTo(0.2);
        }

        @Test void disabled() {
            var p = RetryPolicy.disabled();
            assertThat(p.maxAttempts()).isZero();
        }

        @Test void shouldRetry_nonRetryableExcluded() {
            var p = RetryPolicy.defaults();
            assertThat(p.shouldRetry(new AuthxAuthException("auth"))).isFalse();
            assertThat(p.shouldRetry(new CircuitBreakerOpenException("cb"))).isFalse();
        }

        @Test void shouldRetry_retryableAllowed() {
            var p = RetryPolicy.defaults();
            assertThat(p.shouldRetry(new AuthxConnectionException("conn"))).isTrue();
        }

        @Test void shouldRetry_withExplicitRetryOn() {
            var p = RetryPolicy.builder()
                .retryOn(AuthxConnectionException.class)
                .build();
            assertThat(p.shouldRetry(new AuthxConnectionException("conn"))).isTrue();
            assertThat(p.shouldRetry(new AuthxAuthException("auth"))).isFalse();
        }

        @Test void delayForAttempt_exponentialBackoff() {
            var p = RetryPolicy.builder()
                .baseDelay(Duration.ofMillis(100))
                .multiplier(2.0)
                .jitterFactor(0) // no jitter for deterministic test
                .maxDelay(Duration.ofSeconds(10))
                .build();
            assertThat(p.delayForAttempt(0)).isEqualTo(Duration.ofMillis(100));
            assertThat(p.delayForAttempt(1)).isEqualTo(Duration.ofMillis(200));
            assertThat(p.delayForAttempt(2)).isEqualTo(Duration.ofMillis(400));
        }

        @Test void delayForAttempt_cappedByMaxDelay() {
            var p = RetryPolicy.builder()
                .baseDelay(Duration.ofSeconds(1))
                .multiplier(10.0)
                .jitterFactor(0)
                .maxDelay(Duration.ofSeconds(5))
                .build();
            assertThat(p.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(5));
        }

        @Test void delayForAttempt_withJitter_staysInBounds() {
            var p = RetryPolicy.builder()
                .baseDelay(Duration.ofMillis(100))
                .multiplier(1.0)
                .jitterFactor(0.5)
                .maxDelay(Duration.ofSeconds(10))
                .build();
            for (int i = 0; i < 100; i++) {
                var delay = p.delayForAttempt(0);
                assertThat(delay.toMillis()).isBetween(0L, 200L);
            }
        }
    }

    // ---- ResourcePolicy ----
    @Nested class ResourcePolicyTest {
        @Test void defaults() {
            var p = ResourcePolicy.defaults();
            assertThat(p.cache()).isNotNull();
            assertThat(p.readConsistency()).isNotNull();
            assertThat(p.retry()).isNotNull();
            assertThat(p.circuitBreaker()).isNotNull();
            assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(5));
        }

        @Test void mergeWith_childWins() {
            var child = ResourcePolicy.builder()
                .timeout(Duration.ofSeconds(1))
                .build();
            var parent = ResourcePolicy.defaults();
            var merged = child.mergeWith(parent);

            assertThat(merged.timeout()).isEqualTo(Duration.ofSeconds(1));
            // null fields in child inherit from parent
            assertThat(merged.cache()).isNotNull();
            assertThat(merged.retry()).isNotNull();
        }

        @Test void mergeWith_parentFallback() {
            var child = ResourcePolicy.builder().build(); // all null
            var parent = ResourcePolicy.defaults();
            var merged = child.mergeWith(parent);

            assertThat(merged.timeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(merged.cache()).isNotNull();
        }

        @Test void builderAllFields() {
            var p = ResourcePolicy.builder()
                .cache(CachePolicy.disabled())
                .readConsistency(ReadConsistency.strong())
                .retry(RetryPolicy.disabled())
                .circuitBreaker(CircuitBreakerPolicy.disabled())
                .timeout(Duration.ofSeconds(3))
                .build();
            assertThat(p.cache().enabled()).isFalse();
            assertThat(p.readConsistency()).isEqualTo(ReadConsistency.strong());
            assertThat(p.retry().maxAttempts()).isZero();
            assertThat(p.circuitBreaker().enabled()).isFalse();
            assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(3));
        }
    }

    // ---- ReadConsistency ----
    @Nested class ReadConsistencyTest {
        @Test void minimizeLatencyIsSingleton() {
            assertThat(ReadConsistency.minimizeLatency()).isSameAs(ReadConsistency.minimizeLatency());
        }

        @Test void sessionIsSingleton() {
            assertThat(ReadConsistency.session()).isSameAs(ReadConsistency.session());
        }

        @Test void snapshotIsSingleton() {
            assertThat(ReadConsistency.snapshot()).isSameAs(ReadConsistency.snapshot());
        }

        @Test void strongIsSingleton() {
            assertThat(ReadConsistency.strong()).isSameAs(ReadConsistency.strong());
        }

        @Test void boundedStaleness() {
            var rc = ReadConsistency.boundedStaleness(Duration.ofSeconds(10));
            assertThat(rc).isInstanceOf(ReadConsistency.BoundedStaleness.class);
            assertThat(((ReadConsistency.BoundedStaleness) rc).maxStaleness()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test void sealedSubtypes() {
            assertThat(ReadConsistency.minimizeLatency()).isInstanceOf(ReadConsistency.MinimizeLatency.class);
            assertThat(ReadConsistency.session()).isInstanceOf(ReadConsistency.Session.class);
            assertThat(ReadConsistency.snapshot()).isInstanceOf(ReadConsistency.Snapshot.class);
            assertThat(ReadConsistency.strong()).isInstanceOf(ReadConsistency.Strong.class);
        }
    }
}
