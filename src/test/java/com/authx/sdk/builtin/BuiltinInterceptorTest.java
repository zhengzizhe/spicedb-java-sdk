package com.authx.sdk.builtin;

import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
import com.authx.sdk.model.enums.SdkAction;
import com.authx.sdk.spi.AttributeKey;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.spi.SdkInterceptor.CheckChain;
import com.authx.sdk.spi.SdkInterceptor.WriteChain;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class BuiltinInterceptorTest {

    // ---- ValidationInterceptor ----
    @Nested class ValidationInterceptorTest {

        private final ValidationInterceptor interceptor = new ValidationInterceptor();

        private CheckChain checkChain(String resType, String resId, String perm) {
            var ctx = new SdkInterceptor.OperationContext(SdkAction.CHECK, resType, resId, perm, "user", "alice");
            var request = CheckRequest.of(
                ResourceRef.of(resType != null ? resType : "document", resId != null ? resId : "1"),
                Permission.of(perm != null ? perm : "view"),
                SubjectRef.of("user", "alice"),
                Consistency.minimizeLatency());
            return new CheckChain() {
                @Override public CheckRequest request() { return request; }
                @Override public CheckResult proceed(CheckRequest req) {
                    return CheckResult.allowed("tok");
                }
                @Override public SdkInterceptor.OperationContext operationContext() { return ctx; }
                @Override public <T> T attr(AttributeKey<T> key) { return key.defaultValue(); }
                @Override public <T> void attr(AttributeKey<T> key, T value) {}
            };
        }

        @Test void validInputPassesThrough() {
            var result = interceptor.interceptCheck(checkChain("document", "doc-1", "view"));
            assertThat(result.hasPermission()).isTrue();
        }

        @Test void invalidResourceType_uppercase() {
            assertThatThrownBy(() -> interceptor.interceptCheck(checkChain("Document", "doc-1", "view")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resource type");
        }

        @Test void invalidResourceType_startsWithDigit() {
            assertThatThrownBy(() -> interceptor.interceptCheck(checkChain("1document", "doc-1", "view")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resource type");
        }

        @Test void invalidResourceId_specialChars() {
            assertThatThrownBy(() -> interceptor.interceptCheck(checkChain("document", "doc@#$", "view")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resource ID");
        }

        @Test void invalidPermission_uppercase() {
            assertThatThrownBy(() -> interceptor.interceptCheck(checkChain("document", "doc-1", "View")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission");
        }

        @Test void emptyFieldsAreSkipped() {
            // Empty strings should not trigger validation
            var result = interceptor.interceptCheck(checkChain("", "", ""));
            assertThat(result).isNotNull();
        }

        @Test void nullFieldsAreSkipped() {
            var result = interceptor.interceptCheck(checkChain(null, null, null));
            assertThat(result).isNotNull();
        }

        @Test void validResourceIdWithSlashAndPipe() {
            var result = interceptor.interceptCheck(checkChain("document", "org/team|doc-1", "view"));
            assertThat(result.hasPermission()).isTrue();
        }

        @Test void resourceType_withUnderscore() {
            var result = interceptor.interceptCheck(checkChain("resource_type", "id-1", "view_all"));
            assertThat(result.hasPermission()).isTrue();
        }
    }

    // ---- DebugInterceptor ----
    @Nested class DebugInterceptorTest {

        private final DebugInterceptor interceptor = new DebugInterceptor();

        @Test void interceptCheckLogsAndReturns() {
            var ctx = new SdkInterceptor.OperationContext(SdkAction.CHECK, "doc", "1", "view", "user", "alice");
            var request = CheckRequest.of(ResourceRef.of("doc", "1"), Permission.of("view"),
                SubjectRef.of("user", "alice"), Consistency.minimizeLatency());

            CheckChain chain = new CheckChain() {
                @Override public CheckRequest request() { return request; }
                @Override public CheckResult proceed(CheckRequest req) {
                    return CheckResult.allowed("tok");
                }
                @Override public SdkInterceptor.OperationContext operationContext() { return ctx; }
                @Override public <T> T attr(AttributeKey<T> key) { return key.defaultValue(); }
                @Override public <T> void attr(AttributeKey<T> key, T value) {}
            };

            var result = interceptor.interceptCheck(chain);
            assertThat(result.hasPermission()).isTrue();
        }

        @Test void interceptCheckPropagatesException() {
            var ctx = new SdkInterceptor.OperationContext(SdkAction.CHECK, "doc", "1", "view", "user", "alice");
            var request = CheckRequest.of(ResourceRef.of("doc", "1"), Permission.of("view"),
                SubjectRef.of("user", "alice"), Consistency.minimizeLatency());

            CheckChain chain = new CheckChain() {
                @Override public CheckRequest request() { return request; }
                @Override public CheckResult proceed(CheckRequest req) {
                    throw new RuntimeException("transport error");
                }
                @Override public SdkInterceptor.OperationContext operationContext() { return ctx; }
                @Override public <T> T attr(AttributeKey<T> key) { return key.defaultValue(); }
                @Override public <T> void attr(AttributeKey<T> key, T value) {}
            };

            assertThatThrownBy(() -> interceptor.interceptCheck(chain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("transport error");
        }

        @Test void interceptWriteLogsAndReturns() {
            var ctx = new SdkInterceptor.OperationContext(SdkAction.WRITE, "doc", "1", "editor", "user", "alice");
            var request = new WriteRequest(java.util.List.of());

            WriteChain chain = new WriteChain() {
                @Override public WriteRequest request() { return request; }
                @Override public GrantResult proceed(WriteRequest req) {
                    return new GrantResult("tok", 1);
                }
                @Override public SdkInterceptor.OperationContext operationContext() { return ctx; }
                @Override public <T> T attr(AttributeKey<T> key) { return key.defaultValue(); }
            };

            var result = interceptor.interceptWrite(chain);
            assertThat(result.count()).isEqualTo(1);
        }

        @Test void interceptWritePropagatesException() {
            var ctx = new SdkInterceptor.OperationContext(SdkAction.WRITE, "doc", "1", "editor", "user", "alice");
            var request = new WriteRequest(java.util.List.of());

            WriteChain chain = new WriteChain() {
                @Override public WriteRequest request() { return request; }
                @Override public GrantResult proceed(WriteRequest req) {
                    throw new RuntimeException("write error");
                }
                @Override public SdkInterceptor.OperationContext operationContext() { return ctx; }
                @Override public <T> T attr(AttributeKey<T> key) { return key.defaultValue(); }
            };

            assertThatThrownBy(() -> interceptor.interceptWrite(chain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("write error");
        }
    }
}
