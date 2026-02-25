package com.trading.drg.fn.finance;

/**
 * Z-Score (Standard Score).
 * 
 * z = (x - mean) / stdDev
 * 
 * Useful for normalizing signals.
 */
public class ZScore extends AbstractFn1 {
    // Composition: Uses HistVol logic internally but needs to access mean too.
    // Re-implementing logic to avoid object creation/getters overhead on hot path.

    private final double[] window;
    private final int size;
    private int head = 0;
    private int count = 0;

    private double sum = 0.0;
    private double sumSq = 0.0;

    public ZScore(int size) {
        if (size < 2)
            throw new IllegalArgumentException("Size must be >= 2");
        this.size = size;
        this.window = new double[size];
    }

    @Override
    protected double calculate(double input) {
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
        window[head] = input;
        sum += input;
        sumSq += input * input;

        // Advance pointer
        head++;
        if (head >= size)
            head = 0;

        if (count < 2)
            return 0.0; // Typically 0 Z-score for single point or just wait?
        // Z-score undefined for < 2 points usually, but 0 is safe placeholder.

        double mean = sum / count;
        double variance = (sumSq / count) - (mean * mean);

        if (variance <= 1e-9)
            return 0.0; // Avoid division by zero/small variance

        double stdDev = Math.sqrt(variance);
        return (input - mean) / stdDev;
    }

}
