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
        if (Double.isNaN(input)) {
            return Double.NaN;
        }

        window[head] = input;
        head = (head + 1) % size;
        if (count < size)
            count++;

        double min = Double.MAX_VALUE;

        // The window is not yet full, so we only consider the 'count' elements
        // that have been added. These elements are not necessarily contiguous
        // from index 0 if 'head' has wrapped around.
        // We need to iterate through the 'count' valid elements in the circular buffer.
        for (int i = 0; i < count; i++) {
            // Calculate the actual index in the circular buffer
            // The elements are stored starting from (head - count + size) % size
            // and going up to (head - 1 + size) % size
            int currentIdx = (head - count + i + size) % size;
            if (window[currentIdx] < min) {
                min = window[currentIdx];
            }
        }
        return min;
    }
}
