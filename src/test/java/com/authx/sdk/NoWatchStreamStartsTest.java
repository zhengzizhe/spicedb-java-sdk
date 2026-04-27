package com.authx.sdk;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the 2026-04-18 Watch subsystem removal: building an
 * SDK client must not spawn any authx-sdk-watch thread.
 */
class NoWatchStreamStartsTest {

    @Test
    void inMemoryClient_noWatchThreadStarted() throws Exception {
        int before = countAuthxWatchThreads();
        try (AuthxClient client = AuthxClient.inMemory()) {
            // Give a moment for any rogue background thread to start.
            Thread.sleep(100);
            int after = countAuthxWatchThreads();
            assertThat(after)
                    .as("AuthxClient.inMemory() must not start any 'authx-sdk-watch' thread")
                    .isEqualTo(before);
        }
    }

    private static int countAuthxWatchThreads() {
        Thread[] threads = new Thread[Math.max(64, Thread.activeCount() * 2)];
        int n = Thread.enumerate(threads);
        return (int) Arrays.stream(threads, 0, n)
                .filter(t -> t != null && t.getName().startsWith("authx-sdk-watch"))
                .count();
    }
}
