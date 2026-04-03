package com.authcses.sdk.spi;

/**
 * Clock abstraction for testability. Replace System.currentTimeMillis() and System.nanoTime().
 *
 * <pre>
 * // Production (default)
 * SdkClock.SYSTEM
 *
 * // Test (controllable time)
 * var clock = new SdkClock.Fixed(1000L);
 * clock.advanceMs(5000); // advance 5 seconds
 * </pre>
 */
public interface SdkClock {

    long currentTimeMillis();

    long nanoTime();

    /** Default system clock. */
    SdkClock SYSTEM = new SdkClock() {
        @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
        @Override public long nanoTime() { return System.nanoTime(); }
    };

    /** Fixed/controllable clock for testing. */
    class Fixed implements SdkClock {
        private volatile long millis;
        private volatile long nanos;

        public Fixed(long initialMillis) {
            this.millis = initialMillis;
            this.nanos = initialMillis * 1_000_000;
        }

        @Override public long currentTimeMillis() { return millis; }
        @Override public long nanoTime() { return nanos; }

        public void advanceMs(long ms) {
            millis += ms;
            nanos += ms * 1_000_000;
        }

        public void setMillis(long millis) {
            this.millis = millis;
            this.nanos = millis * 1_000_000;
        }
    }
}
