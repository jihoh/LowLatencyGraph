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
    /** Fast-path flag to avoid System.nanoTime() on the hot path */
    private boolean hasErrored = false;

    public ErrorRateLimiter() {
        this(1000);
    }

    public ErrorRateLimiter(long minIntervalMillis) {
        this.minIntervalNanos = minIntervalMillis * 1_000_000;
    }

    public void log(String message, Throwable t) {
        hasErrored = true;
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
        if (!hasErrored) {
            return false;
        }
        
        boolean stillOpen = (System.nanoTime() - lastLogTime) < minIntervalNanos;
        if (!stillOpen) {
            // Cooldown expired, we can try computing again
            hasErrored = false;
        }
        return stillOpen;
    }
}
