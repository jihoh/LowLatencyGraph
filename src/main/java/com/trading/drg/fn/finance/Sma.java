package com.trading.drg.fn.finance;

/**
 * Simple Moving Average (SMA) using a Ring Buffer.
 * 
 * Performance: O(1) update.
 * Keeps a running sum and a circular buffer of history.
 */
public class Sma extends AbstractFn1 {
    private final double[] window;
    private final int size;
    private int head = 0;
    private int count = 0;
    private double sum = 0.0;

    public Sma(int size) {
        if (size < 1)
            throw new IllegalArgumentException("Size must be >= 1");
        this.size = size;
        this.window = new double[size];
    }

    @Override
    protected double calculate(double input) {
        if (count < size) {
            // Fill phase
            window[head] = input;
            sum += input;
            head = (head + 1) % size;
            count++;
            return sum / count;
        } else {
            // Full phase: remove old, add new
            double old = window[head];
            sum -= old;
            sum += input;
            window[head] = input;
            head = (head + 1) % size;
            return sum / size;
        }
    }

}
