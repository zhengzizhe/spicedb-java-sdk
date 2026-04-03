package com.authcses.sdk.builtin;

import com.authcses.sdk.spi.SdkInterceptor;

import java.util.regex.Pattern;

/**
 * Validates resource type, ID, permission, subject before hitting SpiceDB.
 * Catches typos and illegal characters at the SDK layer with clear messages.
 */
public class ValidationInterceptor implements SdkInterceptor {

    private static final Pattern RESOURCE_TYPE_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,127}");
    private static final Pattern RESOURCE_ID_PATTERN = Pattern.compile("[a-zA-Z0-9/_|\\-]{1,1024}");
    private static final Pattern PERMISSION_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,127}");

    @Override
    public void before(OperationContext ctx) {
        String resType = ctx.resourceType();
        String resId = ctx.resourceId();
        String perm = ctx.permission();

        if (resType != null && !resType.isEmpty()) {
            if (!RESOURCE_TYPE_PATTERN.matcher(resType).matches()) {
                throw new IllegalArgumentException(
                        "Invalid resource type \"" + resType + "\". "
                        + "Must match [a-z][a-z0-9_]{0,127}. "
                        + "Only lowercase letters, digits, underscores. Must start with a letter.");
            }
        }

        if (resId != null && !resId.isEmpty()) {
            if (!RESOURCE_ID_PATTERN.matcher(resId).matches()) {
                throw new IllegalArgumentException(
                        "Invalid resource ID \"" + truncate(resId, 50) + "\". "
                        + "Must match [a-zA-Z0-9/_|-]{1,1024}. Max 1024 characters.");
            }
        }

        if (perm != null && !perm.isEmpty()) {
            if (!PERMISSION_PATTERN.matcher(perm).matches()) {
                throw new IllegalArgumentException(
                        "Invalid permission/relation \"" + perm + "\". "
                        + "Must match [a-z][a-z0-9_]{0,127}.");
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
