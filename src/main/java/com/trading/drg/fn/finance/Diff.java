package com.trading.drg.fn.finance;

/**
 * First Difference.
 * <p>
 * Formula: {@code y[t] = x[t] - x[t-1]}
 */
public class Diff extends AbstractFn1 {
    private double prev;
    private boolean initialized = false;

    @Override
    protected double calculate(double input) {
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
