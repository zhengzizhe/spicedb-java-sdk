package com.authx.sdk.trace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Constants + formatters for SDK structured-logging fields. All MDC keys
 * are prefixed {@code authx.*} to avoid collisions with business MDC keys.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@link Slf4jMdcBridge#push(Map)} — writes these keys to SLF4J MDC
 *       when SLF4J is on the classpath</li>
 *   <li>{@link #suffixPerm}, {@link #suffixRel} — append a
 *       {@code  [type=... res=... perm|rel=... subj=...]} suffix to
 *       WARN+ log messages for readers without SLF4J</li>
 * </ul>
 */
public final class LogFields {

    public static final String KEY_TRACE_ID = "authx.traceId";
    public static final String KEY_SPAN_ID = "authx.spanId";
    public static final String KEY_ACTION = "authx.action";
    public static final String KEY_RESOURCE_TYPE = "authx.resourceType";
    public static final String KEY_RESOURCE_ID = "authx.resourceId";
    public static final String KEY_PERMISSION = "authx.permission";
    public static final String KEY_RELATION = "authx.relation";
    public static final String KEY_SUBJECT = "authx.subject";
    public static final String KEY_CONSISTENCY = "authx.consistency";
    public static final String KEY_RETRY_ATTEMPT = "authx.retry.attempt";
    public static final String KEY_RETRY_MAX = "authx.retry.max";
    public static final String KEY_CB_STATE = "authx.cb.state";
    public static final String KEY_CAVEAT = "authx.caveat";
    public static final String KEY_EXPIRES_AT = "authx.expiresAt";
    public static final String KEY_ZED_TOKEN = "authx.zedToken";

    // Note: authx.result and authx.errorType are span attributes only, not MDC
    // (too dynamic per-call and only relevant at call completion).

    private LogFields() {}

    /**
     * Low-level suffix builder. Callers usually prefer {@link #suffixPerm}
     * or {@link #suffixRel} which encode the perm/rel distinction at call
     * site.
     *
     * @param resourceType e.g. "document"
     * @param resourceId   e.g. "doc-1"
     * @param permOrRelFragment caller-formatted fragment like "perm=view"
     *                          or "rel=editor" (already containing the key);
     *                          pass {@code null} to omit
     * @param subjectRef   e.g. "user:alice" or "group:eng#member"
     * @return {@code  [type=... res=... perm|rel=... subj=...]} — leading
     *         space + brackets included, or empty string if all four fields
     *         are blank
     */
    public static String suffix(String resourceType, String resourceId,
                                 String permOrRelFragment, String subjectRef) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(resourceType)) sb.append(" type=").append(resourceType);
        if (!isBlank(resourceId)) sb.append(" res=").append(resourceId);
        if (!isBlank(permOrRelFragment)) sb.append(" ").append(permOrRelFragment);
        if (!isBlank(subjectRef)) sb.append(" subj=").append(subjectRef);
        if (sb.length() == 0) return "";
        return " [" + sb.substring(1) + "]";
    }

    /** Build suffix with {@code perm=<value>} labeling. Null / empty perm → omitted. */
    public static String suffixPerm(String resourceType, String resourceId,
                                     String permission, String subjectRef) {
        return suffix(resourceType, resourceId,
                isBlank(permission) ? null : "perm=" + permission, subjectRef);
    }

    /** Build suffix with {@code rel=<value>} labeling. Null / empty relation → omitted. */
    public static String suffixRel(String resourceType, String resourceId,
                                    String relation, String subjectRef) {
        return suffix(resourceType, resourceId,
                isBlank(relation) ? null : "rel=" + relation, subjectRef);
    }

    /**
     * Build a non-null MDC map, omitting blank/null values. Insertion-ordered
     * ({@link LinkedHashMap}) so Logback pattern access and JSON encoder
     * output are deterministic.
     */
    public static Map<String, String> toMdcMap(String action, String resourceType,
                                                 String resourceId, String permission,
                                                 String relation, String subjectRef,
                                                 String consistency) {
        Map<String, String> m = new LinkedHashMap<>();
        putIfNotBlank(m, KEY_ACTION, action);
        putIfNotBlank(m, KEY_RESOURCE_TYPE, resourceType);
        putIfNotBlank(m, KEY_RESOURCE_ID, resourceId);
        putIfNotBlank(m, KEY_PERMISSION, permission);
        putIfNotBlank(m, KEY_RELATION, relation);
        putIfNotBlank(m, KEY_SUBJECT, subjectRef);
        putIfNotBlank(m, KEY_CONSISTENCY, consistency);
        return m;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    private static void putIfNotBlank(Map<String, String> m, String k, String v) {
        if (!isBlank(v)) m.put(k, v);
    }
}
