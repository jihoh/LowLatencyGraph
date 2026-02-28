package com.trading.drg.fn.finance;

/**
 * Rolling Minimum over a window.
 * <p>
 * Formula: {@code y[t] = min(x[t-N+1], ..., x[t])}
 */
public class RollingMin extends AbstractFn1 {
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
    protected double calculate(double input) {
        window[head] = input;
        head = (head + 1) % size; // Fixed ring buffer logic in previous RollingMax? No, RollingMin has different
                                  // logic?
        // Wait, RollingMax used: head++; if(head>=size) head=0;
        // RollingMin used: head = (head + 1) % size;
        // Both are fine.

        if (count < size)
            count++;

        double min = Double.MAX_VALUE;

        // Correction for RollingMin logic:
        // This implementation iterates 'count' times, calculating indices.
        // This is correct for a ring buffer where we need to find the Min.
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
