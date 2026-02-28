package com.trading.drg.util;

import org.apache.logging.log4j.Logger;

/**
 * Limits error logging rate in high-frequency loops to prevent log flooding.
 */
public class ErrorRateLimiter {
    /** Bound logger instance. */
    private final Logger logger;
    /** Minimum interval between logs in nanos. */
    private final long minIntervalNanos;
    /** Timestamp of last log. */
    private long lastLogTime = 0;

    public ErrorRateLimiter(Logger logger, long minIntervalMillis) {
        this.logger = logger;
        this.minIntervalNanos = minIntervalMillis * 1_000_000;
    }

    public void log(String message, Throwable t) {
        long now = System.nanoTime();
        if (now - lastLogTime > minIntervalNanos) {
            lastLogTime = now;
            logger.error(message + " (Throttled)", t);
        }
    }
}
