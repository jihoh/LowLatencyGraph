package com.trading.drg.fn.finance;

/**
 * Z-Score (Standard Score).
 *
 * z = (x - mean) / stdDev
 *
 * Useful for normalizing signals.
 *
 * Variance is computed with a two-pass compensated approach (subtracting the
 * mean before squaring) to avoid catastrophic cancellation in the naive
 * formula (sumSq/N - mean^2) when values are large relative to their variance.
 */
public class ZScore extends AbstractFn1 {
    private final double[] window;
    private final int size;
    private int head = 0;
    private int count = 0;

    private double sum = 0.0;

    public ZScore(int size) {
        if (size < 2)
            throw new IllegalArgumentException("Size must be >= 2");
        this.size = size;
        this.window = new double[size];
    }

    @Override
    protected double calculate(double input) {
        // 1. Evict the oldest element's contribution from the rolling sum
        if (count >= size) {
            sum -= window[head];
        } else {
            count++;
        }

        // 2. Insert new value
        window[head] = input;
        sum += input;

        head++;
        if (head >= size)
            head = 0;

        if (count < 2)
            return 0.0; // Z-score undefined for fewer than 2 points

        double mean = sum / count;

        // 3. Compensated variance: sum (x - mean)^2 over the filled window.
        //    This prevents catastrophic cancellation for large-valued inputs.
        double compensatedSumSq = 0.0;
        int start = (head + size - count) % size;
        for (int i = 0; i < count; i++) {
            double dev = window[(start + i) % size] - mean;
            compensatedSumSq += dev * dev;
        }

        double variance = compensatedSumSq / count;

        // Guard: tiny negative values can arise from floating-point rounding;
        // clamp to zero before sqrt to avoid producing NaN.
        if (variance < 1e-9)
            return 0.0;

        double stdDev = Math.sqrt(variance);
        return (input - mean) / stdDev;
    }
}
