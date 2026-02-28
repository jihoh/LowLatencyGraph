package com.trading.drg.fn.finance;

import com.trading.drg.api.DynamicState;
import com.trading.drg.fn.FnN;
import com.trading.drg.util.ErrorRateLimiter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base class for array-input functions. Handles error rate limiting.
 * <p>
 * Formula: {@code y = f(X)}
 */
public abstract class AbstractFnN implements FnN, DynamicState {
    private final Logger log = LogManager.getLogger(this.getClass());
    private final ErrorRateLimiter limiter = new ErrorRateLimiter(log, 1000);

    @Override
    public final double apply(double[] inputs) {
        if (inputs == null) {
            return Double.NaN;
        }
        for (double v : inputs) {
            if (Double.isNaN(v)) {
                return Double.NaN;
            }
        }
        try {
            return calculate(inputs);
        } catch (Throwable t) {
            limiter.log("Error evaluating " + this.getClass().getSimpleName(), t);
            return Double.NaN;
        }
    }

    /**
     * Subclasses implement the actual logic here.
     */
    protected abstract double calculate(double[] inputs);
}
