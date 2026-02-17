package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn1;

/**
 * Z-Score (Standard Score).
 * 
 * z = (x - mean) / stdDev
 * 
 * Useful for normalizing signals.
 */
public class ZScore implements Fn1 {
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
    public double apply(double input) {
        if (count < size) {
            window[head] = input;
            sum += input;
            sumSq += input * input;
            head = (head + 1) % size;
            count++;
        } else {
            double old = window[head];
            sum += input - old;
            sumSq += (input * input) - (old * old);
            window[head] = input;
            head = (head + 1) % size;
        }

        if (count < 2)
            return 0.0;

        double mean = sum / count;
        double variance = (sumSq / count) - (mean * mean);

        if (variance < 1e-12)
            return 0.0; // Avoid divide by zero

        double stdDev = Math.sqrt(variance);
        return (input - mean) / stdDev;
    }
}
