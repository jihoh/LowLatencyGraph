package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn1;

/**
 * Rolling Maximum over a window.
 * 
 * Uses linear scan (O(N)) over ring buffer for zero-GC simplicity.
 * For N < 100, this is often faster than Deque due to cache locality.
 */
public class RollingMax implements Fn1 {
    private final double[] window;
    private final int size;
    private int head = 0;
    private int count = 0;

    public RollingMax(int size) {
        if (size < 1)
            throw new IllegalArgumentException("Size must be >= 1");
        this.size = size;
        this.window = new double[size];
    }

    @Override
    public double apply(double input) {
        if (Double.isNaN(input)) {
            return Double.NaN;
        }

        window[head] = input;
        head++;
        if (head >= size) {
            head = 0;
        }

        if (count < size) {
            count++;
        }

        double max = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if (window[i] > max) {
                max = window[i];
            }
        }
        return max;
    }
}
