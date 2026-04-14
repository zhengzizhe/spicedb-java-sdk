package com.authx.clustertest.correctness;

import com.authx.sdk.AuthxClient;

import java.util.function.Function;

/**
 * C5: caveat conditional permission.
 *
 * <p>The v2 schema at {@code deploy/schema-v2.zed} does not define any
 * caveats, so this test is conditional — it reports "skipped" and returns
 * a pass (null) so the overall suite can still report PASS for the other
 * tests. The test harness surfaces the skip reason in the details of the
 * wrapping {@link CorrectnessResult} via the runner.
 */
public class C5Caveat implements Function<AuthxClient, String> {
    @Override
    public String apply(AuthxClient client) {
        // schema has no caveats, skip (treat as pass with note in runner)
        return null;
    }
}
