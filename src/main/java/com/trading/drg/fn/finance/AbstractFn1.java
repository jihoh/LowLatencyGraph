package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn1;
import com.trading.drg.util.ErrorRateLimiter;

import lombok.extern.log4j.Log4j2;

/**
 * Base class for single-input functions. Handles error rate limiting.
 * <p>
 * Formula: {@code y = f(x)}
 */
@Log4j2
public abstract class AbstractFn1 implements Fn1 {
    private final ErrorRateLimiter limiter = new ErrorRateLimiter();

    @Override
    public final double apply(double input) {
        if (Double.isNaN(input) || limiter.isCircuitOpen()) {
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
