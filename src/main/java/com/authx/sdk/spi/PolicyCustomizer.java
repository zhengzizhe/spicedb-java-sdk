package com.authx.sdk.spi;

import com.authx.sdk.policy.PolicyRegistry;

/**
 * Business-facing SPI for customizing the per-resource-type
 * {@link com.authx.sdk.policy.PolicyRegistry} at client build time.
 *
 * <p>The SDK ships generic defaults (3 retries, CircuitBreaker on,
 * SESSION consistency). Production deployments almost always need to
 * tune these: tighter consistency for write-after-read workflows, circuit
 * breaker thresholds calibrated to the actual p99 latency of the SpiceDB
 * cluster, etc. Those decisions belong to the <em>business team</em>, not
 * to the infrastructure team that wires the SDK — but before this SPI
 * they had to live inside the same Spring {@code @Configuration} class
 * as the gRPC target and preshared key, mixing concerns.
 *
 * <p>A {@code PolicyCustomizer} is a pure function from a freshly-constructed
 * {@link PolicyRegistry.Builder} to a tuned one. The infrastructure-layer
 * config class wires {@code AuthxClient} as usual, and the business layer
 * provides its own {@code PolicyCustomizer} bean — Spring (or plain Java
 * code) stitches them together at build time via
 * {@link com.authx.sdk.AuthxClientBuilder#customize(PolicyCustomizer)}.
 *
 * <p>Multiple customizers compose in registration order, so a base "default
 * sane values" customizer and a team-specific override customizer can both
 * apply without either one needing to know about the other.
 *
 * <p>Example — business team code:
 * <pre>
 * &#64;Component
 * public class BillingPolicies implements PolicyCustomizer {
 *     &#64;Override
 *     public void customize(PolicyRegistry.Builder policies) {
 *         policies.defaultPolicy(ResourcePolicy.builder()
 *                 .readConsistency(ReadConsistency.session())
 *                 .retry(RetryPolicy.defaults())
 *                 .build())
 *             .forResourceType("invoice", ResourcePolicy.builder()
 *                 .readConsistency(ReadConsistency.strong())
 *                 .build());
 *     }
 * }
 * </pre>
 *
 * <p>The infrastructure config — which is now pure plumbing with zero
 * business decisions — looks like:
 * <pre>
 * &#64;Configuration
 * public class SpiceDbInfraConfig {
 *     &#64;Bean(destroyMethod = "close")
 *     public AuthxClient authxClient(SpiceDbProperties props,
 *                                    List&lt;PolicyCustomizer&gt; policyCustomizers) {
 *         var builder = AuthxClient.builder()
 *             .connection(c -&gt; c.targets(props.targets()).presharedKey(props.key()))
 *             .features(f -&gt; f.telemetry(true).shutdownHook(true));
 *         policyCustomizers.forEach(builder::customize);
 *         return builder.build();
 *     }
 * }
 * </pre>
 */
@FunctionalInterface
public interface PolicyCustomizer {

    /**
     * Apply business-specific policy tuning to the supplied builder.
     *
     * <p>The builder is pre-populated with SDK defaults; customizers should
     * typically call {@link PolicyRegistry.Builder#defaultPolicy} or
     * {@link PolicyRegistry.Builder#forResourceType} to narrow/override, not
     * replace the entire registry from scratch.
     */
    void customize(PolicyRegistry.Builder policies);
}
