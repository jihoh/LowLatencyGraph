package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn1;

/**
 * Exponential Moving Average (EWMA).
 * 
 * Formula:
 * y[t] = alpha * x[t] + (1 - alpha) * y[t-1]
 */
public class Ewma implements Fn1 {
    private final double alpha;
    private double state;
    private boolean initialized = false;

    public Ewma(double alpha) {
        if (alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("Alpha must be in (0, 1]");
        }
        this.alpha = alpha;
    }

    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager
            .getLogger(Ewma.class);
    private final com.trading.drg.util.ErrorRateLimiter limiter = new com.trading.drg.util.ErrorRateLimiter(log, 1000);

    @Override
    public double apply(double input) {
        try {
            if (Double.isNaN(input)) {
                return Double.NaN;
            }

            // Handle First Tick
            if (!initialized) {
                state = input;
                initialized = true;
                return state;
            }

            // Classic EWMA Formula:
            // New = Alpha * Input + (1 - Alpha) * Old
            state = alpha * input + (1.0 - alpha) * state;
            return state;
        } catch (Throwable t) {
            limiter.log("Error in Ewma", t);
            return Double.NaN;
        }
    }
}
