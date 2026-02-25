package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn1;
import com.trading.drg.util.ErrorRateLimiter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base class for all Fn1 implementations.
 * Handles try-catch wrapping and error rate limiting automatically.
 */
public abstract class AbstractFn1 implements Fn1 {
    private final Logger log = LogManager.getLogger(this.getClass());
    private final ErrorRateLimiter limiter = new ErrorRateLimiter(log, 1000);

    @Override
    public final double apply(double input) {
        if (Double.isNaN(input)) {
            return Double.NaN;
        }
        try {
            return calculate(input);
        } catch (Throwable t) {
            limiter.log("Error evaluating " + this.getClass().getSimpleName(), t);
            return Double.NaN;
        }
    }

    /**
     * Subclasses implement the actual logic here.
     */
    protected abstract double calculate(double input);
}
