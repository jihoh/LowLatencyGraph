package com.trading.drg.util;

import org.apache.logging.log4j.Logger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A utility class to limit the rate of error logging.
 * Useful in high-frequency loops to prevent log flooding during persistent
 * failure conditions.
 */
public class ErrorRateLimiter {
    private final Logger logger;
    private final long minIntervalNanos;
    private final AtomicLong lastLogTime = new AtomicLong(0);

    public ErrorRateLimiter(Logger logger, long minIntervalMillis) {
        this.logger = logger;
        this.minIntervalNanos = minIntervalMillis * 1_000_000;
    }

    public void log(String message, Throwable t) {
        long now = System.nanoTime();
        long last = lastLogTime.get();
        if (now - last > minIntervalNanos) {
            // Check-and-set to ensure only one thread logs per interval in concurrent
            // scenarios
            if (lastLogTime.compareAndSet(last, now)) {
                logger.error(message + " (Throttled)", t);
            }
        }
    }
}
