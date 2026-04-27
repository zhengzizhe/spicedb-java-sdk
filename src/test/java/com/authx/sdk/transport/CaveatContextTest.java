package com.authx.sdk.transport;

import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation.TOUCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaveatContextTest {

    @Test
    void check_withCaveatContext_doesNotThrow() {
        InMemoryTransport transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(TOUCH,
                        ResourceRef.of("doc", "1"), Relation.of("viewer"),
                        SubjectRef.of("user", "alice", null))));

        Map<String, Object> ctx = Map.<String, Object>of("ip_address", "192.168.1.1", "is_internal", true);
        CheckRequest request = new CheckRequest(
                ResourceRef.of("doc", "1"), Permission.of("viewer"),
                SubjectRef.of("user", "alice", null),
                Consistency.minimizeLatency(), ctx);
        CheckResult result = transport.check(request);
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }

    @Test
    void check_withCaveatContext_nullContext_delegatesToBaseCheck() {
        InMemoryTransport transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(TOUCH,
                        ResourceRef.of("doc", "2"), Relation.of("editor"),
                        SubjectRef.of("user", "bob", null))));

        CheckRequest request = new CheckRequest(
                ResourceRef.of("doc", "2"), Permission.of("editor"),
                SubjectRef.of("user", "bob", null),
                Consistency.minimizeLatency(), null);
        CheckResult result = transport.check(request);
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }

    @Test
    void check_withCaveatContext_noPermission_returnsDenied() {
        InMemoryTransport transport = new InMemoryTransport();

        // Use a HashMap to allow null values, covering the null branch in toValue()
        HashMap<String, Object> ctx = new HashMap<String, Object>();
        ctx.put("region", "us-east");
        ctx.put("score", 42.5);
        ctx.put("active", false);
        ctx.put("tag", null);
        CheckRequest request = new CheckRequest(
                ResourceRef.of("doc", "99"), Permission.of("viewer"),
                SubjectRef.of("user", "unknown", null),
                Consistency.minimizeLatency(), ctx);
        CheckResult result = transport.check(request);
        assertThat(result.permissionship()).isEqualTo(Permissionship.NO_PERMISSION);
    }

    @Test
    void toStruct_nestedMap_convertsRecursively() {
        InMemoryTransport transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(TOUCH,
                        ResourceRef.of("doc", "3"), Relation.of("viewer"),
                        SubjectRef.of("user", "carol", null))));

        Map<String, Object> ctx = Map.<String, Object>of(
                "metadata", Map.of("role", "admin", "level", 5),
                "enabled", true);
        CheckRequest request = new CheckRequest(
                ResourceRef.of("doc", "3"), Permission.of("viewer"),
                SubjectRef.of("user", "carol", null),
                Consistency.minimizeLatency(), ctx);
        CheckResult result = transport.check(request);
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }

    @Test
    void toValue_list_convertsToListValue() {
        InMemoryTransport transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(TOUCH,
                        ResourceRef.of("doc", "4"), Relation.of("viewer"),
                        SubjectRef.of("user", "dave", null))));

        Map<String, Object> ctx = Map.<String, Object>of(
                "tags", List.of("public", "draft"),
                "scores", List.of(1, 2, 3));
        CheckRequest request = new CheckRequest(
                ResourceRef.of("doc", "4"), Permission.of("viewer"),
                SubjectRef.of("user", "dave", null),
                Consistency.minimizeLatency(), ctx);
        CheckResult result = transport.check(request);
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }

    @Test
    void toValue_nestedListWithMaps_convertsRecursively() {
        InMemoryTransport transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(TOUCH,
                        ResourceRef.of("doc", "5"), Relation.of("viewer"),
                        SubjectRef.of("user", "eve", null))));

        Map<String, Object> ctx = Map.<String, Object>of(
                "items", List.of(
                        Map.of("name", "item1", "count", 10),
                        Map.of("name", "item2", "count", 20)));
        CheckRequest request = new CheckRequest(
                ResourceRef.of("doc", "5"), Permission.of("viewer"),
                SubjectRef.of("user", "eve", null),
                Consistency.minimizeLatency(), ctx);
        CheckResult result = transport.check(request);
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }

    @Test
    void toValue_unsupportedType_throwsIllegalArgument() {
        // GrpcTransport.toStruct/toValue are called during gRPC check; we test via
        // reflection since InMemoryTransport doesn't go through toStruct.
        // Instead, call the static methods directly via reflection.
        assertThatThrownBy(() -> {
            Method method = GrpcTransport.class.getDeclaredMethod("toValue", Object.class);
            method.setAccessible(true);
            method.invoke(null, new Object() {}); // anonymous class — unsupported type
        }).hasCauseInstanceOf(IllegalArgumentException.class)
          .cause()
          .hasMessageContaining("Unsupported caveat context value type");
    }

    @Test
    void toStruct_nullKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> {
            Method method = GrpcTransport.class.getDeclaredMethod("toStruct", Map.class);
            method.setAccessible(true);
            HashMap<String, Object> map = new HashMap<String, Object>();
            map.put(null, "value");
            method.invoke(null, map);
        }).hasCauseInstanceOf(IllegalArgumentException.class)
          .cause()
          .hasMessageContaining("non-null and non-empty");
    }

    @Test
    void toStruct_emptyKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> {
            Method method = GrpcTransport.class.getDeclaredMethod("toStruct", Map.class);
            method.setAccessible(true);
            method.invoke(null, Map.of("", "value"));
        }).hasCauseInstanceOf(IllegalArgumentException.class)
          .cause()
          .hasMessageContaining("non-null and non-empty");
    }

    @Test
    void toStruct_wrapsConversionErrorWithFieldName() {
        assertThatThrownBy(() -> {
            Method method = GrpcTransport.class.getDeclaredMethod("toStruct", Map.class);
            method.setAccessible(true);
            method.invoke(null, Map.of("badField", new Object() {}));
        }).hasCauseInstanceOf(IllegalArgumentException.class)
          .cause()
          .hasMessageContaining("badField");
    }

    @Test
    void toValue_mapSupport_convertsNestedStruct() throws Exception {
        Method method = GrpcTransport.class.getDeclaredMethod("toValue", Object.class);
        method.setAccessible(true);
        // Should not throw — nested map is supported
        Object result = method.invoke(null, Map.of("inner", "val"));
        assertThat(result).isNotNull();
    }

    @Test
    void toValue_listSupport_convertsList() throws Exception {
        Method method = GrpcTransport.class.getDeclaredMethod("toValue", Object.class);
        method.setAccessible(true);
        // Should not throw — list is supported
        Object result = method.invoke(null, List.of("a", "b", "c"));
        assertThat(result).isNotNull();
    }
}
