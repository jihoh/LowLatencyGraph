package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn3;
import com.trading.drg.util.ErrorRateLimiter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base class for triple-input functions. Handles error rate limiting.
 * <p>
 * Formula: {@code y = f(x_1, x_2, x_3)}
 */
public abstract class AbstractFn3 implements Fn3 {
    private final Logger log = LogManager.getLogger(this.getClass());
    private final ErrorRateLimiter limiter = new ErrorRateLimiter(log, 1000);

    @Override
    public final double apply(double input1, double input2, double input3) {
        if (Double.isNaN(input1) || Double.isNaN(input2) || Double.isNaN(input3)) {
            return Double.NaN;
        }
        try {
            return calculate(input1, input2, input3);
        } catch (Throwable t) {
            limiter.log("Error evaluating " + this.getClass().getSimpleName(), t);
            return Double.NaN;
        }
    }

    /**
     * Subclasses implement the actual logic here.
     */
    protected abstract double calculate(double input1, double input2, double input3);
}
