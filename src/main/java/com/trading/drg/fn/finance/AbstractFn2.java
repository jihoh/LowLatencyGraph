package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn2;
import com.trading.drg.util.ErrorRateLimiter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base class for all Fn2 implementations.
 * Handles try-catch wrapping and error rate limiting automatically.
 */
public abstract class AbstractFn2 implements Fn2 {
    private final Logger log = LogManager.getLogger(this.getClass());
    private final ErrorRateLimiter limiter = new ErrorRateLimiter(log, 1000);

    @Override
    public final double apply(double input1, double input2) {
        if (Double.isNaN(input1) || Double.isNaN(input2)) {
            return Double.NaN;
        }
        try {
            return calculate(input1, input2);
        } catch (Throwable t) {
            limiter.log("Error evaluating " + this.getClass().getSimpleName(), t);
            return Double.NaN;
        }
    }

    /**
     * Subclasses implement the actual logic here.
     */
    protected abstract double calculate(double input1, double input2);
}
