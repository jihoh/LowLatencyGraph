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

    @Override
    public double apply(double input) {
        if (!initialized) {
            state = input;
            initialized = true;
            return state;
        }

        state = alpha * input + (1.0 - alpha) * state;
        return state;
    }
}
