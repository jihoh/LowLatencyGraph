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
        window[head] = input;
        head = (head + 1) % size;
        if (count < size)
            count++;

        // Linear scan to find max
        double max = -Double.MAX_VALUE;
        int limit = (count == size) ? size : count;
        max = window[0];
        for (int i = 1; i < limit; i++) {
            if (window[i] > max)
                max = window[i];
        }
        return max;
    }
}
