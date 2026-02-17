package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn1;

/**
 * Logarithmic Return.
 * 
 * y[t] = ln(x[t] / x[t-1])
 */
public class LogReturn implements Fn1 {
    private double prev;
    private boolean initialized = false;

    @Override
    public double apply(double input) {
        if (!initialized) {
            prev = input;
            initialized = true;
            return 0.0;
        }

        // Guard against division by zero or log of non-positive
        if (prev <= 0 || input <= 0) {
            prev = input;
            return 0.0; // or NaN, but 0 is safer for pipelines
        }

        double ret = Math.log(input / prev);
        prev = input;
        return ret;
    }
}
