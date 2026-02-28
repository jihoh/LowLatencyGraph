package com.trading.drg.fn.finance;

/**
 * Moving Average Convergence Divergence (MACD). Returns the MACD Line.
 * <p>
 * Formula: {@code MACD = EWMA_fast(x) - EWMA_slow(x)}
 */
public class Macd extends AbstractFn1 {
    private final Ewma fast;
    private final Ewma slow;

    public Macd(int fastPeriod, int slowPeriod) {
        // Alpha = 2 / (N + 1)
        this.fast = new Ewma(2.0 / (fastPeriod + 1));
        this.slow = new Ewma(2.0 / (slowPeriod + 1));
    }

    @Override
    protected double calculate(double input) {
        double f = fast.apply(input);
        double s = slow.apply(input);
        return f - s;
    }
}
