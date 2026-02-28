package com.trading.drg.fn.finance;

/**
 * Historical Volatility (Rolling Standard Deviation).
 *
 * Uses a two-stage approach for numerical stability:
 * - Stage 1 (O(1)): Maintains a rolling sum to compute the mean cheaply.
 * - Stage 2 (O(N)): Computes variance by summing (x - mean)^2 over the window.
 *
 * This avoids the catastrophic cancellation that occurs in the naive formula
 * Var = (Sum(x^2) / N) - mean^2 when values are large relative to the
 * variance (e.g., prices in the thousands with tiny variance).
 *
 * For log-return inputs the naive formula is numerically adequate, but the
 * compensated pass is cheap (window sizes are typically 20-252) and keeps the
 * implementation correct for all inputs.
 */

public class HistVol extends AbstractFn1 {
    private final double[] window;
    private final int size;
    private int head = 0;
    private int count = 0;

    private double sum = 0.0;

    public HistVol(int size) {
        if (size < 2)
            throw new IllegalArgumentException("Size must be >= 2 for standard deviation");
        this.size = size;
        this.window = new double[size];
    }

    @Override
    protected double calculate(double logReturn) {
        // 1. Evict oldest element from rolling sum
        if (count >= size) {
            sum -= window[head];
        } else {
            count++;
        }

        // 2. Insert new value
        window[head] = logReturn;
        sum += logReturn;

        // Advance circular pointer
        head++;
        if (head >= size)
            head = 0;

        if (count < 2)
            return 0.0; // Need at least 2 points for variance

        // 3. Compute variance using compensated sum of squared deviations.
        //    Subtracting the mean before squaring prevents catastrophic cancellation.
        double mean = sum / count;
        double compensatedSumSq = 0.0;
        // Iterate only over the filled portion of the circular buffer
        int start = (head + size - count) % size;
        for (int i = 0; i < count; i++) {
            double dev = window[(start + i) % size] - mean;
            compensatedSumSq += dev * dev;
        }

        double variance = compensatedSumSq / count;

        return Math.sqrt(variance);
    }
}
