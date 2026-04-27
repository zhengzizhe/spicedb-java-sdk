package com.authx.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckMatrixTest {

    @Test
    void pointLookup_returnsStoredValue() {
        com.authx.sdk.model.CheckMatrix m = CheckMatrix.builder()
                .add("doc-1", "view", "alice", true)
                .add("doc-1", "view", "bob", false)
                .build();

        assertThat(m.allowed("doc-1", "view", "alice")).isTrue();
        assertThat(m.allowed("doc-1", "view", "bob")).isFalse();
    }

    @Test
    void allowedForAbsentCell_returnsFalseNeverNull() {
        com.authx.sdk.model.CheckMatrix m = CheckMatrix.builder()
                .add("doc-1", "view", "alice", true)
                .build();

        // Absent cells: not in the matrix at all. Contract says false, not null.
        assertThat(m.allowed("doc-2", "view", "alice")).isFalse();
        assertThat(m.allowed("doc-1", "edit", "alice")).isFalse();
        assertThat(m.allowed("doc-1", "view", "carol")).isFalse();
        assertThat(m.contains("doc-1", "view", "alice")).isTrue();
        assertThat(m.contains("doc-1", "edit", "alice")).isFalse();
    }

    @Test
    void allAllowed_andAnyAllowed_wholeMatrix() {
        com.authx.sdk.model.CheckMatrix allOn = CheckMatrix.builder()
                .add("doc-1", "view", "alice", true)
                .add("doc-1", "view", "bob", true)
                .build();
        assertThat(allOn.allAllowed()).isTrue();
        assertThat(allOn.anyDenied()).isFalse();

        com.authx.sdk.model.CheckMatrix mixed = CheckMatrix.builder()
                .add("doc-1", "view", "alice", true)
                .add("doc-1", "view", "bob", false)
                .build();
        assertThat(mixed.allAllowed()).isFalse();
        assertThat(mixed.anyAllowed()).isTrue();
        assertThat(mixed.anyDenied()).isTrue();
    }

    @Test
    void perResourcePerPermissionSlice() {
        com.authx.sdk.model.CheckMatrix m = CheckMatrix.builder()
                .add("doc-1", "view", "alice", true)
                .add("doc-1", "view", "bob", true)
                .add("doc-1", "edit", "alice", true)
                .add("doc-1", "edit", "bob", false)
                .build();

        assertThat(m.allAllowed("doc-1", "view")).isTrue();
        assertThat(m.allAllowed("doc-1", "edit")).isFalse();
        assertThat(m.anyAllowed("doc-1", "edit")).isTrue();
    }

    @Test
    void subjectAxisQueries() {
        com.authx.sdk.model.CheckMatrix m = CheckMatrix.builder()
                .add("doc-1", "view", "alice", true)
                .add("doc-2", "view", "alice", true)
                .add("doc-1", "view", "bob", false)
                .add("doc-2", "view", "bob", false)
                .build();

        assertThat(m.allAllowedForSubject("alice")).isTrue();
        assertThat(m.allAllowedForSubject("bob")).isFalse();
        assertThat(m.anyAllowedForSubject("bob")).isFalse();
        assertThat(m.anyAllowedForSubject("alice")).isTrue();
    }

    @Test
    void forResourceAndSubject_returnsPermissionMap() {
        com.authx.sdk.model.CheckMatrix m = CheckMatrix.builder()
                .add("doc-1", "view", "alice", true)
                .add("doc-1", "edit", "alice", false)
                .add("doc-1", "comment", "alice", true)
                .add("doc-2", "view", "alice", false)   // different resource — should be ignored
                .build();

        java.util.Map<java.lang.String,java.lang.Boolean> slice = m.forResourceAndSubject("doc-1", "alice");
        assertThat(slice).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("view", true, "edit", false, "comment", true));
    }

    @Test
    void axisAccessors_preserveInsertionOrder() {
        com.authx.sdk.model.CheckMatrix m = CheckMatrix.builder()
                .add("doc-1", "view", "alice", true)
                .add("doc-2", "view", "alice", true)
                .add("doc-3", "view", "alice", true)
                .build();

        assertThat(m.resources()).containsExactly("doc-1", "doc-2", "doc-3");
        assertThat(m.permissions()).containsExactly("view");
        assertThat(m.subjects()).containsExactly("alice");
    }

    @Test
    void forEach_visitsEveryCell() {
        com.authx.sdk.model.CheckMatrix m = CheckMatrix.builder()
                .add("doc-1", "view", "alice", true)
                .add("doc-1", "edit", "alice", false)
                .build();

        List<String> visited = new ArrayList<>();
        m.forEach((id, perm, sub, allowed) ->
                visited.add(id + "|" + perm + "|" + sub + "|" + allowed));

        assertThat(visited).containsExactly(
                "doc-1|view|alice|true",
                "doc-1|edit|alice|false");
    }

    @Test
    void emptyMatrix_hasSizeZeroAndReturnsFalseForAllQueries() {
        com.authx.sdk.model.CheckMatrix m = CheckMatrix.builder().build();
        assertThat(m.size()).isZero();
        assertThat(m.allowed("x", "y", "z")).isFalse();
        assertThat(m.allAllowed()).isTrue();       // vacuously true
        assertThat(m.anyAllowed()).isFalse();
        assertThat(m.anyDenied()).isFalse();
    }

    @Test
    void lastWriteWins_onDuplicateCell() {
        com.authx.sdk.model.CheckMatrix m = CheckMatrix.builder()
                .add("doc-1", "view", "alice", false)
                .add("doc-1", "view", "alice", true)   // overwrites
                .build();

        assertThat(m.allowed("doc-1", "view", "alice")).isTrue();
    }
}
