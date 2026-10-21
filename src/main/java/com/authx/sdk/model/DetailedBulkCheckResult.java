package com.authx.sdk.model;

import com.authx.sdk.exception.AuthxProtocolException;
import java.util.List;
import java.util.Objects;

/** Ordered bulk outcomes, including conditions and revision tokens; duplicate cells remain distinct. */
public record DetailedBulkCheckResult(List<Item> items) {
    public DetailedBulkCheckResult { items = List.copyOf(items); }

    public record Item(CheckRequest request, CheckResult result) {
        public Item {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(result, "result");
        }
    }

    /** Require actual results evaluated at one revision, rather than assuming every batch is one snapshot. */
    public String requireSingleSnapshotToken() {
        if (items.isEmpty()) throw new IllegalStateException("A snapshot requires at least one check");
        String token = items.getFirst().result().zedToken();
        if (token == null || token.isBlank()
                || items.stream().anyMatch(item -> !token.equals(item.result().zedToken()))) {
            throw new AuthxProtocolException("Bulk checks did not return one shared snapshot token");
        }
        return token;
    }
}
