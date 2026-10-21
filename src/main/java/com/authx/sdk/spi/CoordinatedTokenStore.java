package com.authx.sdk.spi;

import com.authx.sdk.model.WriteResult;
import java.util.function.Supplier;

/**
 * Required consistency coordination for one shared authorization namespace.
 * Unlike {@link DistributedTokenStore}, this is not a best-effort get/set cache.
 *
 * <p>All SDK clients affecting this namespace must use the same coordinator. The
 * namespace must include every related resource type, not merely the type being checked.
 * Implementations must serialize mutations across processes, durably mark a write
 * in progress before calling the mutation, publish its returned token before success,
 * and make required reads unavailable after an uncertain write or lost lock. A lease
 * expiry must never expose the previous token as safe. No arbitrary TTL may silently
 * erase this state. Reconciliation/bootstrap after untracked or uncertain writes is
 * owned by the integrator. Opaque token strings must not be sorted or compared.
 *
 * <p>The backing SpiceDB deployment must preserve the write ordering relied on by
 * this coordinator. This SPI does not implement Redis locks or database transactions,
 * and cannot guarantee changes made outside its mutation boundary. Implementations
 * must respect the calling gRPC context's deadline/cancellation and fail closed.
 */
public interface CoordinatedTokenStore {
    /**
     * Execute the supplier exactly once under cross-process coordination. Return the
     * supplier's result only after its nonblank token has been durably published.
     * An uncertain exception must not leave an earlier token usable by required readers.
     * An authoritative rejection proving no write occurred may retain the prior bound.
     */
    WriteResult executeWrite(Supplier<WriteResult> mutation);

    /**
     * Read the current, durably published namespace token linearly. Throw on missing,
     * pending, uncertain, expired, or unavailable state; never fall back to local state.
     */
    String readRequiredToken();
}
