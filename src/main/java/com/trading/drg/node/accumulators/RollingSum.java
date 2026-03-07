package com.trading.drg.node.accumulators;

import com.trading.drg.api.WindowedAccumulator;

/**
 * Strict O(1) rolling sum accumulator.
 */
public class RollingSum implements WindowedAccumulator {
    private double sum = 0;

    @Override
    public void onAdd(double value) {
        sum += value;
    }

    @Override
    public void onRemove(double value) {
        sum -= value;
    }

    @Override
    public double result() {
        return sum;
    }
}
