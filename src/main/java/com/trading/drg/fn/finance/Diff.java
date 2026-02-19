package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn1;

/**
 * First Difference.
 * 
 * y[t] = x[t] - x[t-1]
 */
public class Diff implements Fn1 {
    private double prev;
    private boolean initialized = false;

    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager
            .getLogger(Diff.class);
    private final com.trading.drg.util.ErrorRateLimiter limiter = new com.trading.drg.util.ErrorRateLimiter(log, 1000);

    @Override
    public double apply(double input) {
        try {
            if (Double.isNaN(input)) {
                return Double.NaN;
            }

            if (!initialized) {
                prev = input;
                initialized = true;
                return 0.0; // No diff on first tick
            }

            double diff = input - prev;
            prev = input;
            return diff;
        } catch (Throwable t) {
            limiter.log("Error in Diff", t);
            return Double.NaN;
        }
    }
}
