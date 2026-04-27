package com.authx.sdk.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequestObjectTest {

    @Test void checkRequest_of() {
        CheckRequest req = CheckRequest.of(
            ResourceRef.of("document", "d1"),
            Permission.of("view"),
            SubjectRef.of("user", "alice"),
            Consistency.full()
        );
        assertThat(req.resource().type()).isEqualTo("document");
        assertThat(req.resource().id()).isEqualTo("d1");
        assertThat(req.permission().name()).isEqualTo("view");
        assertThat(req.subject().id()).isEqualTo("alice");
        assertThat(req.consistency()).isInstanceOf(Consistency.Full.class);
        assertThat(req.caveatContext()).isNull();
    }

    @Test void checkRequest_from_bridgesStrings() {
        CheckRequest req = CheckRequest.of("document", "d1", "view", "user", "alice", Consistency.minimizeLatency());
        assertThat(req.resource()).isEqualTo(ResourceRef.of("document", "d1"));
        assertThat(req.permission()).isEqualTo(Permission.of("view"));
        assertThat(req.subject()).isEqualTo(SubjectRef.of("user", "alice", null));
        assertThat(req.consistency()).isInstanceOf(Consistency.MinimizeLatency.class);
    }

    @Test void checkRequest_from_withRelation() {
        CheckRequest req = CheckRequest.of("document", "d1", "view", "group", "admins", "member", Consistency.full());
        assertThat(req.subject().type()).isEqualTo("group");
        assertThat(req.subject().id()).isEqualTo("admins");
        assertThat(req.subject().relation()).isEqualTo("member");
    }

    @Test void checkRequest_toKey() {
        CheckRequest req = CheckRequest.of("document", "d1", "view", "user", "alice", Consistency.full());
        CheckKey key = req.toKey();
        assertThat(key.resource()).isEqualTo(ResourceRef.of("document", "d1"));
        assertThat(key.permission()).isEqualTo(Permission.of("view"));
        assertThat(key.subject()).isEqualTo(SubjectRef.of("user", "alice", null));
    }

    @Test void checkRequest_rejectsNull() {
        assertThatThrownBy(() -> CheckRequest.of(null, Permission.of("view"), SubjectRef.of("user", "a"), Consistency.full()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void lookupSubjectsRequest() {
        LookupSubjectsRequest req = new LookupSubjectsRequest(ResourceRef.of("document", "d1"), Permission.of("view"), "user", 100);
        assertThat(req.resource().id()).isEqualTo("d1");
        assertThat(req.permission().name()).isEqualTo("view");
        assertThat(req.subjectType()).isEqualTo("user");
        assertThat(req.limit()).isEqualTo(100);
    }

    @Test void lookupSubjectsRequest_defaultLimit() {
        LookupSubjectsRequest req = new LookupSubjectsRequest(ResourceRef.of("document", "d1"), Permission.of("view"), "user");
        assertThat(req.limit()).isEqualTo(0);
    }

    @Test void lookupResourcesRequest() {
        LookupResourcesRequest req = new LookupResourcesRequest("document", Permission.of("view"), SubjectRef.of("user", "alice"), 50);
        assertThat(req.resourceType()).isEqualTo("document");
        assertThat(req.subject().id()).isEqualTo("alice");
        assertThat(req.limit()).isEqualTo(50);
    }

    @Test void lookupResourcesRequest_defaultLimit() {
        LookupResourcesRequest req = new LookupResourcesRequest("document", Permission.of("view"), SubjectRef.of("user", "alice"));
        assertThat(req.limit()).isEqualTo(0);
    }
}
