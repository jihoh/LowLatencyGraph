package com.trading.drg.fn.finance;

import com.trading.drg.fn.FnN;
import com.trading.drg.util.ErrorRateLimiter;

import lombok.extern.log4j.Log4j2;

/**
 * Base class for array-input functions. Handles error rate limiting.
 * <p>
 * Formula: {@code y = f(X)}
 */
@Log4j2
public abstract class AbstractFnN implements FnN {
    private final ErrorRateLimiter limiter = new ErrorRateLimiter();

    @Override
    public final double apply(double[] inputs) {
        if (inputs == null || limiter.isCircuitOpen()) {
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
