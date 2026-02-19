package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn1;

/**
 * Historical Volatility (Rolling Standard Deviation).
 * 
 * Uses Welford's algorithm or Sum of Squares to compute rolling variance in
 * O(1).
 * Here we use the naive sum-of-squares method for performance, suitable for
 * small windows (N < 1000).
 * For extreme precision or large N, Welford is better but slower.
 * 
 * Var = (Sum(x^2) / N) - (Sum(x) / N)^2
 */
public class HistVol implements Fn1 {
    private final double[] window;
    private final int size;
    private int head = 0;
    private int count = 0;

    private double sum = 0.0;
    private double sumSq = 0.0;

    public HistVol(int size) {
        if (size < 2)
            throw new IllegalArgumentException("Size must be >= 2 for standard deviation");
        this.size = size;
        this.window = new double[size];
    }

    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager
            .getLogger(HistVol.class);
    private final com.trading.drg.util.ErrorRateLimiter limiter = new com.trading.drg.util.ErrorRateLimiter(log, 1000);

    @Override
    public double apply(double logReturn) {
        try {
            if (Double.isNaN(logReturn)) {
                return Double.NaN;
            }

            // Standard Deviation of Log Returns * Sqrt(AnnualizationFactor)

            // 1. Update Sum and SumSq
            // Remove old
            if (count >= size) {
                double old = window[head];
                sum -= old;
                sumSq -= old * old;
            } else {
                count++;
            }

            // Add new
            window[head] = logReturn;
            sum += logReturn;
            sumSq += logReturn * logReturn;

            // Advance pointer
            head++;
            if (head >= size)
                head = 0;

            if (count < 2)
                return 0.0; // Need at least 2 points for variance

            double mean = sum / count;
            double variance = (sumSq / count) - (mean * mean);

            // Numerical stability check
            if (variance < 0)
                variance = 0.0;

            // The original code did not have an annualizationFactor.
            // Assuming it should return the standard deviation directly if not provided.
            return Math.sqrt(variance);
        } catch (Throwable t) {
            limiter.log("Error in HistVol", t);
            return Double.NaN;
        }
    }
}
