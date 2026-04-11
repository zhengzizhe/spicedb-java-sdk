package com.authx.sdk.spi;

import com.authx.sdk.AuthxClientBuilder;

/**
 * General-purpose escape hatch SPI for customizing an
 * {@link com.authx.sdk.AuthxClient} at build time, used when
 * {@link PolicyCustomizer} is too narrow.
 *
 * <p>Unlike {@code PolicyCustomizer}, which is scoped to the
 * {@link com.authx.sdk.policy.PolicyRegistry} only, a
 * {@code AuthxClientCustomizer} receives the entire builder and can
 * touch anything — connection, cache, features, interceptors,
 * {@code SdkComponents}, health probes, everything. Use this when
 * a business needs to, for example:
 *
 * <ul>
 *   <li>Register a custom {@code SdkInterceptor} for cross-cutting
 *       audit logging
 *   <li>Inject a Redis-backed {@code DistributedTokenStore} via
 *       {@code extend(e -> e.components(...))} to get SESSION
 *       consistency across multiple JVMs
 *   <li>Attach a {@code DuplicateDetector} to the Watch stream for
 *       cursor-replay deduplication
 *   <li>Register a custom {@code HealthProbe} that wraps the SDK's
 *       default with a team-specific "maintenance window" check
 * </ul>
 *
 * <p>Customizers run <b>after</b> infrastructure wiring (targets,
 * preshared key, cache on/off, feature toggles) and <b>before</b>
 * {@link AuthxClientBuilder#build()} runs, so they see a fully
 * configured builder and can layer on top of it. Multiple customizers
 * compose in registration order.
 *
 * <p>Prefer {@link PolicyCustomizer} when your customization is purely
 * policy-related; reach for {@code AuthxClientCustomizer} only when
 * you need the broader surface area.
 *
 * <pre>
 * &#64;Component
 * public class AuditInterceptorCustomizer implements AuthxClientCustomizer {
 *     private final AuditInterceptor interceptor;
 *
 *     &#64;Override
 *     public void customize(AuthxClientBuilder builder) {
 *         builder.extend(e -&gt; e.addInterceptor(interceptor));
 *     }
 * }
 * </pre>
 */
@FunctionalInterface
public interface AuthxClientCustomizer {

    /**
     * Apply a customization to the builder. Called once per builder, after
     * infrastructure config has been applied and before {@code build()} runs.
     */
    void customize(AuthxClientBuilder builder);
}
