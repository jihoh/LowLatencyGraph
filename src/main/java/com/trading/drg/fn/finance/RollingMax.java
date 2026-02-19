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

    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager
            .getLogger(RollingMax.class);
    private final com.trading.drg.util.ErrorRateLimiter limiter = new com.trading.drg.util.ErrorRateLimiter(log, 1000);

    @Override
    public double apply(double input) {
        try {
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
            // Linear scan for correctness in O(N)
            for (int i = 0; i < size; i++) {
                // Note: we must scan all valid elements.
                // The original logic scanned 'count' elements starting from 0.
                // But wait, if we are using a ring buffer, the 'count' elements are not
                // 0..count.
                // They are scattered if wrapped?
                // Actually, if count < size, we just filled 0..count.
                // If count == size, the buffer is full, we must scan everything.
                // So "i < count" logic in original code assumes 0..count-1 are the valid
                // elements.
                // Once full, count == size, so we scan 0..size-1.
                // Is this correct? Yes, because we write to 'head' but we don't zero out old
                // values?
                // Well, we overwrite them.
                // So scanning the whole array is correct once full.
            }

            // Re-implementing existing logic but safely inside try-catch
            for (int i = 0; i < count; i++) {
                if (window[i] > max) {
                    max = window[i];
                }
            }
            return max;
        } catch (Throwable t) {
            limiter.log("Error in RollingMax", t);
            return Double.NaN;
        }
    }
}
