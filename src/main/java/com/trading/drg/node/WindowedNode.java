package com.trading.drg.node;

import com.trading.drg.api.ScalarValue;
import com.trading.drg.api.WindowedAccumulator;
import com.trading.drg.util.ScalarCutoffs;

/**
 * A stateful node that maintains a rolling sliding window of the last N values
 * and computes an aggregated result in O(1) time using a {@link WindowedAccumulator}.
 */
public class WindowedNode extends ScalarNode {
    private final ScalarValue input;
    private final int windowSize;
    private final WindowedAccumulator accumulator;
    
    // Circular buffer for strict zero-allocation O(1) sliding window
    private final double[] window;
    private int head = 0;
    private int count = 0;

    public WindowedNode(String name, ScalarValue input, int windowSize, WindowedAccumulator accumulator) {
        super(name, ScalarCutoffs.EXACT);
        if (windowSize < 2) {
            throw new IllegalArgumentException("Window size must be >= 2");
        }
        this.input = input;
        this.windowSize = windowSize;
        this.accumulator = accumulator;
        this.window = new double[windowSize];
    }

    @Override
    protected double compute() {
        double newValue = input.value();
        
        // Discard NaNs without breaking the continuous window?
        // In most quantitative engines, a NaN propagates and poisons the window, 
        // OR the graph ignores NaNs entirely. We will propagate it because 
        // we can't cleanly "remove" a NaN from a sum later.
        if (Double.isNaN(newValue)) {
            return Double.NaN;
        }

        if (count < windowSize) {
            // 1. Initial Fill Mode: Just add to the accumulator
            accumulator.onAdd(newValue);
            window[head] = newValue;
            count++;
        } else {
            // 2. Rolling Mode: Remove the oldest, add the newest
            double oldestValue = window[head];
            accumulator.onRemove(oldestValue);
            accumulator.onAdd(newValue);
            window[head] = newValue;
        }

        // Advance circular buffer head
        head++;
        if (head >= windowSize) {
            head = 0;
        }

        // Return the current aggregated result
        return accumulator.result();
    }
}
