package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn1;

/**
 * Moving Average Convergence Divergence (MACD).
 * 
 * Returns the "MACD Line" (Fast EWMA - Slow EWMA).
 * 
 * To get the "Signal Line", feed this output into another EWMA.
 * To get the "Histogram", subtract the Signal Line from this output.
 */
public class Macd implements Fn1 {
    private final Ewma fast;
    private final Ewma slow;

    public Macd(int fastPeriod, int slowPeriod) {
        // Alpha = 2 / (N + 1)
        this.fast = new Ewma(2.0 / (fastPeriod + 1));
        this.slow = new Ewma(2.0 / (slowPeriod + 1));
    }

    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager
            .getLogger(Macd.class);
    private final com.trading.drg.util.ErrorRateLimiter limiter = new com.trading.drg.util.ErrorRateLimiter(log, 1000);

    @Override
    public double apply(double input) {
        try {
            double f = fast.apply(input);
            double s = slow.apply(input);
            return f - s;
        } catch (Throwable t) {
            limiter.log("Error in Macd", t);
            return Double.NaN;
        }
    }
}
