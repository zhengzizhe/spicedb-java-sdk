package com.authx.sdk.transport;

import com.authx.sdk.model.*;
import com.authx.sdk.spi.SdkInterceptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link InterceptorTransport} — interceptor chain execution for all operation types.
 * Complements the existing InterceptorChainTest with coverage for non-check operations.
 */
class InterceptorTransportTest {

    private InMemoryTransport inner;

    @BeforeEach
    void setup() {
        inner = new InMemoryTransport();
        inner.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));
    }

    @Test
    void deleteRelationshipsGoesThoughOperationChain() {
        var events = new ArrayList<String>();
        SdkInterceptor interceptor = new SdkInterceptor() {
            @Override
            public <T> T interceptOperation(OperationChain<T> chain) {
                events.add("operation-before");
                T result = chain.proceed();
                events.add("operation-after");
                return result;
            }
        };

        var transport = new InterceptorTransport(inner, List.of(interceptor));
        transport.deleteRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.DELETE,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));

        assertThat(events).containsExactly("operation-before", "operation-after");
    }

    @Test
    void readRelationshipsGoesThoughOperationChain() {
        var events = new ArrayList<String>();
        SdkInterceptor interceptor = new SdkInterceptor() {
            @Override
            public <T> T interceptOperation(OperationChain<T> chain) {
                events.add("read-intercepted");
                return chain.proceed();
            }
        };

        var transport = new InterceptorTransport(inner, List.of(interceptor));
        var tuples = transport.readRelationships(
                ResourceRef.of("document", "d1"), Relation.of("editor"), Consistency.minimizeLatency());

        assertThat(tuples).hasSize(1);
        assertThat(events).containsExactly("read-intercepted");
    }

    @Test
    void lookupSubjectsGoesThoughOperationChain() {
        var events = new ArrayList<String>();
        SdkInterceptor interceptor = new SdkInterceptor() {
            @Override
            public <T> T interceptOperation(OperationChain<T> chain) {
                events.add("lookup-subjects-intercepted");
                return chain.proceed();
            }
        };

        var transport = new InterceptorTransport(inner, List.of(interceptor));
        transport.lookupSubjects(new LookupSubjectsRequest(
                ResourceRef.of("document", "d1"), Permission.of("editor"), "user"));

        assertThat(events).containsExactly("lookup-subjects-intercepted");
    }

    @Test
    void lookupResourcesGoesThoughOperationChain() {
        var events = new ArrayList<String>();
        SdkInterceptor interceptor = new SdkInterceptor() {
            @Override
            public <T> T interceptOperation(OperationChain<T> chain) {
                events.add("lookup-resources-intercepted");
                return chain.proceed();
            }
        };

        var transport = new InterceptorTransport(inner, List.of(interceptor));
        transport.lookupResources(new LookupResourcesRequest(
                "document", Permission.of("editor"), SubjectRef.of("user", "alice", null)));

        assertThat(events).containsExactly("lookup-resources-intercepted");
    }

    @Test
    void expandGoesThoughOperationChain() {
        var events = new ArrayList<String>();
        SdkInterceptor interceptor = new SdkInterceptor() {
            @Override
            public <T> T interceptOperation(OperationChain<T> chain) {
                events.add("expand-intercepted");
                return chain.proceed();
            }
        };

        var transport = new InterceptorTransport(inner, List.of(interceptor));
        transport.expand(ResourceRef.of("document", "d1"), Permission.of("editor"), Consistency.minimizeLatency());

        assertThat(events).containsExactly("expand-intercepted");
    }

    @Test
    void checkBulkGoesThoughOperationChain() {
        var events = new ArrayList<String>();
        SdkInterceptor interceptor = new SdkInterceptor() {
            @Override
            public <T> T interceptOperation(OperationChain<T> chain) {
                events.add("bulk-intercepted");
                return chain.proceed();
            }
        };

        var transport = new InterceptorTransport(inner, List.of(interceptor));
        transport.checkBulk(
                CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()),
                List.of(SubjectRef.of("user", "alice", null)));

        assertThat(events).containsExactly("bulk-intercepted");
    }

    @Test
    void checkBulkMultiGoesThoughOperationChain() {
        var events = new ArrayList<String>();
        SdkInterceptor interceptor = new SdkInterceptor() {
            @Override
            public <T> T interceptOperation(OperationChain<T> chain) {
                events.add("bulk-multi-intercepted");
                return chain.proceed();
            }
        };

        var transport = new InterceptorTransport(inner, List.of(interceptor));
        transport.checkBulkMulti(
                List.of(new SdkTransport.BulkCheckItem(
                        ResourceRef.of("document", "d1"),
                        Permission.of("editor"),
                        SubjectRef.of("user", "alice", null))),
                Consistency.minimizeLatency());

        assertThat(events).containsExactly("bulk-multi-intercepted");
    }

    @Test
    void deleteByFilterGoesThoughOperationChain() {
        var events = new ArrayList<String>();
        SdkInterceptor interceptor = new SdkInterceptor() {
            @Override
            public <T> T interceptOperation(OperationChain<T> chain) {
                events.add("delete-filter-intercepted");
                return chain.proceed();
            }
        };

        var transport = new InterceptorTransport(inner, List.of(interceptor));
        transport.deleteByFilter(
                ResourceRef.of("document", "d1"),
                SubjectRef.of("user", "alice", null),
                Relation.of("editor"));

        assertThat(events).containsExactly("delete-filter-intercepted");
    }

    @Test
    void emptyInterceptorsPassThrough() {
        var transport = new InterceptorTransport(inner, List.of());

        var tuples = transport.readRelationships(
                ResourceRef.of("document", "d1"), null, Consistency.minimizeLatency());
        assertThat(tuples).hasSize(1);

        transport.lookupSubjects(new LookupSubjectsRequest(
                ResourceRef.of("document", "d1"), Permission.of("editor"), "user"));

        transport.lookupResources(new LookupResourcesRequest(
                "document", Permission.of("editor"), SubjectRef.of("user", "alice", null)));
    }

    @Test
    void interceptorListIsImmutableCopy() {
        var mutableList = new ArrayList<SdkInterceptor>();
        mutableList.add(new SdkInterceptor() {});

        var transport = new InterceptorTransport(inner, mutableList);

        // Modifying the original list should not affect the transport
        mutableList.clear();

        // Should still work (interceptor list was copied)
        var result = transport.check(CheckRequest.of(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));
        assertThat(result.hasPermission()).isTrue();
    }

    @Test
    void closeDelegatesToInner() {
        var transport = new InterceptorTransport(inner, List.of());
        transport.close();
        assertThat(inner.size()).isZero();
    }
}
