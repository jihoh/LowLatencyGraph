package com.trading.drg.fn.finance;

import com.trading.drg.api.DynamicState;
import com.trading.drg.fn.Fn2;
import com.trading.drg.util.ErrorRateLimiter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base class for dual-input functions. Handles error rate limiting.
 * <p>
 * Formula: {@code y = f(x_1, x_2)}
 */
public abstract class AbstractFn2 implements Fn2, DynamicState {
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
