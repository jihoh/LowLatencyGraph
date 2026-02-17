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

    @Override
    public double apply(double input) {
        double old = 0.0;

        if (count < size) {
            // Fill phase
            window[head] = input;
            sum += input;
            sumSq += input * input;
            head = (head + 1) % size;
            count++;
        } else {
            // Full phase
            old = window[head];
            sum += input - old;
            sumSq += (input * input) - (old * old);
            window[head] = input;
            head = (head + 1) % size;
        }

        if (count < 2)
            return 0.0;

        double mean = sum / count;
        double variance = (sumSq / count) - (mean * mean);

        // Variance can be slightly negative due to floating point errors
        if (variance < 1e-12)
            return 0.0;

        return Math.sqrt(variance);
    }
}
