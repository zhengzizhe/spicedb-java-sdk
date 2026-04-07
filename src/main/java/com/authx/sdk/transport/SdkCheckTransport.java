package com.authx.sdk.transport;

import com.authx.sdk.model.*;

import java.util.List;

/**
 * Transport sub-interface for permission check operations.
 */
public interface SdkCheckTransport {

    CheckResult check(CheckRequest request);

    BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects);

    List<CheckResult> checkBulkMulti(List<SdkTransport.BulkCheckItem> items, Consistency consistency);
}
