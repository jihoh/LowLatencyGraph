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

    @Override
    public double apply(double input) {
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
    }
}
