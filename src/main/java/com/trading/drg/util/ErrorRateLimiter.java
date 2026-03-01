package com.trading.drg.util;

import lombok.extern.log4j.Log4j2;

/**
 * Limits error logging rate in high-frequency loops to prevent log flooding.
 */
@Log4j2
public class ErrorRateLimiter {
    /** Minimum interval between logs in nanos. */
    private final long minIntervalNanos;
    /** Timestamp of last log. */
    private long lastLogTime = 0;

    public ErrorRateLimiter() {
        this(1000);
    }

    public ErrorRateLimiter(long minIntervalMillis) {
        this.minIntervalNanos = minIntervalMillis * 1_000_000;
    }

    public void log(String message, Throwable t) {
        long now = System.nanoTime();
        if (now - lastLogTime > minIntervalNanos) {
            lastLogTime = now;
            log.error(message + " (Throttled)", t);
        }
    }

    /**
     * Checks if the active cooldown period is still in effect to act as a circuit
     * breaker.
     * Prevents hot-loop exception generation by fast-failing nodes before
     * computation.
     */
    public boolean isCircuitOpen() {
        return (System.nanoTime() - lastLogTime) < minIntervalNanos;
    }
}
