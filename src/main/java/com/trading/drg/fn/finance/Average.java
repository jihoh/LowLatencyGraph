package com.trading.drg.fn.finance;

import com.trading.drg.fn.FnN;

public class Average implements FnN {
    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager
            .getLogger(Average.class);
    private final com.trading.drg.util.ErrorRateLimiter limiter = new com.trading.drg.util.ErrorRateLimiter(log, 1000);

    @Override
    public double apply(double[] inputs) {
        try {
            if (inputs == null || inputs.length == 0) {
                return Double.NaN;
            }
            double sum = 0.0;
            for (double d : inputs) {
                if (Double.isNaN(d)) {
                    return Double.NaN;
                }
                sum += d;
            }
            return sum / inputs.length;
        } catch (Throwable t) {
            limiter.log("Error in Average", t);
            return Double.NaN;
        }
    }
}
