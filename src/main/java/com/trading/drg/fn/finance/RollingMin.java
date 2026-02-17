package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn1;

/**
 * Rolling Minimum over a window.
 */
public class RollingMin implements Fn1 {
    private final double[] window;
    private final int size;
    private int head = 0;
    private int count = 0;

    public RollingMin(int size) {
        if (size < 1)
            throw new IllegalArgumentException("Size must be >= 1");
        this.size = size;
        this.window = new double[size];
    }

    @Override
    public double apply(double input) {
        window[head] = input;
        head = (head + 1) % size;
        if (count < size)
            count++;

        double min = Double.MAX_VALUE;

        if (count == size) {
            min = window[0];
            for (int i = 1; i < size; i++) {
                if (window[i] < min)
                    min = window[i];
            }
        } else {
            min = window[0];
            for (int i = 1; i < count; i++) {
                if (window[i] < min)
                    min = window[i];
            }
        }
        return min;
    }
}
