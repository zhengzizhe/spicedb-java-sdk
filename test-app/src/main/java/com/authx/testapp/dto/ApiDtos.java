package com.authx.testapp.dto;

import java.util.List;
import java.util.Map;

/**
 * Request / response bodies for the test-app REST API. Kept together in
 * one compilation unit so the business-resource controllers stay tiny.
 */
public final class ApiDtos {

    private ApiDtos() {}

    /** POST body for "grant this user X role" endpoints. */
    public record UserRequest(String userId) {}

    /** POST body for "grant N users at once" endpoints. */
    public record UsersRequest(List<String> userIds) {}

    /** POST body for "share publicly with an IP allow-list" endpoints. */
    public record PublicShareRequest(List<String> allowedCidrs) {}

    /** POST body for batched multi-document permission checks. */
    public record BatchCheckRequest(String userId, List<BatchCheckItem> items) {
        public record BatchCheckItem(String docId, String permission) {}
    }

    /** Uniform response for single boolean checks. */
    public record CheckResponse(boolean allowed) {}

    /** Response for list-subjects / list-resources endpoints. */
    public record IdsResponse(List<String> ids) {}

    /** Response for {@code checkAll(Document.Perm).by(...)}. */
    public record PermissionMatrixResponse(Map<String, Boolean> permissions) {}

    /** Response for batched multi-document checks. */
    public record BatchCheckResponse(List<BatchCheckOutcome> results) {
        public record BatchCheckOutcome(String docId, String permission, boolean allowed) {}
    }

    /** Response for grant / revoke endpoints that want to report write counts. */
    public record WriteResponse(int writeCount) {}
}
