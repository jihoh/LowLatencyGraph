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
        if (Double.isNaN(input) || input <= 0) {
            return Double.NaN;
        }

        if (!initialized) {
            prev = input;
            initialized = true;
            return 0.0;
        }

        if (prev <= 0) {
            prev = input;
            return Double.NaN;
        }

        double ret = Math.log(input / prev);
        prev = input;
        return ret;
    }
}
