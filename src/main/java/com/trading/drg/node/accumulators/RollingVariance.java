package com.trading.drg.node.accumulators;

import com.trading.drg.api.WindowedAccumulator;

/**
 * Strict O(1) rolling variance and standard deviation accumulator using Welford's Method.
 * Resilient against catastrophic cancellation in floating point math.
 */
public class RollingVariance implements WindowedAccumulator {
    private int count = 0;
    
    // Running Moments
    private double mean = 0;
    private double m2 = 0; // Sum of squares of differences from the current mean

    public RollingVariance(int windowSize) {
        // windowSize is not strictly needed for the Welford accumulator itself as it uses `count`
    }

    @Override
    public void onAdd(double value) {
        count++;
        // Welford's Add
        double delta = value - mean;
        mean += delta / count;
        m2 += delta * (value - mean);
    }

    @Override
    public void onRemove(double value) {
        // Welford's Remove
        double oldDelta = value - mean;
        mean -= oldDelta / (count - 1);
        m2 -= oldDelta * (value - mean);
        count--;
    }

    /**
     * @return The rolling Sample Variance. Returns 0.0 if count < 2.
     */
    @Override
    public double result() {
        if (count < 2) {
            return 0.0;
        }
        double variance = m2 / (count - 1); // Sample variance
        // Clamp floating point strays near zero
        return Math.max(0.0, variance);
    }
}
