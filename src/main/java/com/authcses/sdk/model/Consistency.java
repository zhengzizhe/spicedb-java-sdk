package com.authcses.sdk.model;

/**
 * SpiceDB consistency level for read operations.
 */
public sealed interface Consistency {

    static Consistency minimizeLatency() {
        return MinimizeLatency.INSTANCE;
    }

    static Consistency atLeast(String zedToken) {
        return new AtLeast(zedToken);
    }

    static Consistency atExactSnapshot(String zedToken) {
        return new AtExactSnapshot(zedToken);
    }

    static Consistency full() {
        return Full.INSTANCE;
    }

    record MinimizeLatency() implements Consistency {
        static final MinimizeLatency INSTANCE = new MinimizeLatency();
    }

    record AtLeast(String zedToken) implements Consistency {
    }

    record AtExactSnapshot(String zedToken) implements Consistency {
    }

    record Full() implements Consistency {
        static final Full INSTANCE = new Full();
    }
}
