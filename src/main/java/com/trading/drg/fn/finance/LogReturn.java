package com.trading.drg.fn.finance;

/**
 * Logarithmic Return.
 * <p>
 * Formula: {@code y[t] = ln(x[t] / x[t-1])}
 */

public class LogReturn extends AbstractFn1 {
    private double prev;
    private boolean initialized = false;

    @Override
    protected double calculate(double input) {
        if (input <= 0) {
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
