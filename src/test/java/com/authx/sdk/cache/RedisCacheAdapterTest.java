package com.authx.sdk.cache;

import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class RedisCacheAdapterTest {

    RedisCommands<String, String> commands;
    RedisCacheAdapter adapter;

    @BeforeEach
    void setup() {
        commands = Mockito.mock(RedisCommands.class);
        adapter = new RedisCacheAdapter(commands, 30);
    }

    CheckKey testKey() {
        return CheckKey.of(
                ResourceRef.of("document", "doc-1"),
                Permission.of("view"),
                SubjectRef.of("user", "alice", null));
    }

    @Test
    void get_hit_returnsResult() {
        when(commands.hget("authx:check:document:doc-1", "view:user:alice"))
                .thenReturn("HAS_PERMISSION|tok-42|");
        var result = adapter.get(testKey());
        assertThat(result).isPresent();
        assertThat(result.get().hasPermission()).isTrue();
        assertThat(result.get().zedToken()).isEqualTo("tok-42");
    }

    @Test
    void get_miss_returnsEmpty() {
        when(commands.hget(anyString(), anyString())).thenReturn(null);
        assertThat(adapter.get(testKey())).isEmpty();
    }

    @Test
    void put_callsHsetAndExpire() {
        var result = new CheckResult(Permissionship.HAS_PERMISSION, "tok-1", Optional.empty());
        adapter.put(testKey(), result);
        verify(commands).hset("authx:check:document:doc-1", "view:user:alice", "HAS_PERMISSION|tok-1|");
        verify(commands).expire("authx:check:document:doc-1", 30L);
    }

    @Test
    void invalidate_callsHdel() {
        adapter.invalidate(testKey());
        verify(commands).hdel("authx:check:document:doc-1", "view:user:alice");
    }

    @Test
    void invalidateByIndex_callsDel() {
        adapter.invalidateByIndex("document:doc-1");
        verify(commands).del("authx:check:document:doc-1");
    }

    @Test
    void invalidateAll_predicate_throwsUnsupported() {
        assertThatThrownBy(() -> adapter.invalidateAll(k -> true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void stats_countsHitsAndMisses() {
        when(commands.hget(anyString(), anyString()))
                .thenReturn(null)
                .thenReturn("HAS_PERMISSION|t|");
        adapter.getIfPresent(testKey()); // miss
        adapter.getIfPresent(testKey()); // hit
        var stats = adapter.stats();
        assertThat(stats.hitCount()).isEqualTo(1);
        assertThat(stats.missCount()).isEqualTo(1);
    }

    @Test
    void serialize_deserialize_roundTrip() {
        var original = new CheckResult(Permissionship.CONDITIONAL_PERMISSION, "tok-99",
                Optional.of(Instant.parse("2026-12-31T23:59:59Z")));
        adapter.put(testKey(), original);

        // Capture what was written
        var captor = ArgumentCaptor.forClass(String.class);
        verify(commands).hset(anyString(), anyString(), captor.capture());
        String serialized = captor.getValue();

        // Feed it back as a read
        when(commands.hget(anyString(), anyString())).thenReturn(serialized);
        var deserialized = adapter.getIfPresent(testKey());

        assertThat(deserialized.permissionship()).isEqualTo(Permissionship.CONDITIONAL_PERMISSION);
        assertThat(deserialized.zedToken()).isEqualTo("tok-99");
        assertThat(deserialized.expiresAt()).contains(Instant.parse("2026-12-31T23:59:59Z"));
    }
}
