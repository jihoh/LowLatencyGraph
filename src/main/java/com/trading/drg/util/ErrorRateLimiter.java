package com.trading.drg.util;

import org.apache.logging.log4j.Logger;

/**
 * A utility class to limit the rate of error logging.
 * Useful in high-frequency loops to prevent log flooding during persistent
 * failure conditions.
 */
public class ErrorRateLimiter {
    private final Logger logger;
    private final long minIntervalNanos;
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
