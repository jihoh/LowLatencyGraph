package com.trading.drg.node.accumulators;

import com.trading.drg.api.WindowedAccumulator;

/**
 * Strict O(1) rolling maximum accumulator using a monotonic double-ended queue.
 * Memory is pre-allocated up to windowSize to ensure zero-GC on the hot path.
 */
public class RollingMax implements WindowedAccumulator {
    
    // Values and their insertion "timestamps" (actually just tick counts)
    private final double[] maxDeque;
    private final long[] tickDeque;
    
    // Deque pointers
    private int head = 0;
    private int tail = 0;
    
    private final int windowSize;
    private long currentTick = 0;

    public RollingMax(int windowSize) {
        this.windowSize = windowSize;
        // Circular buffer size + 1 to distinguish full from empty
        int cap = windowSize + 1;
        this.maxDeque = new double[cap];
        this.tickDeque = new long[cap];
    }

    @Override
    public void onAdd(double value) {
        // 1. Maintain monotonically decreasing queue
        // Remove elements from the tail that are smaller than the new value,
        // as they can never be the maximum while the new value is alive.
        while (head != tail) {
            int prevTail = (tail - 1 + maxDeque.length) % maxDeque.length;
            if (maxDeque[prevTail] <= value) {
                // Pop the tail
                tail = prevTail;
            } else {
                break;
            }
        }
        
        // 2. Add new value to the tail
        maxDeque[tail] = value;
        tickDeque[tail] = currentTick;
        tail = (tail + 1) % maxDeque.length;
        
        currentTick++;
    }

    @Override
    public void onRemove(double value) {
        // The value leaving the window might be the current head of our monotonic queue.
        // We know what's leaving by calculating the tick that just expired.
        long expiredTick = currentTick - windowSize;
        
        if (head != tail && tickDeque[head] <= expiredTick) {
            // The max value has expired out of the window, pop the head
            head = (head + 1) % tickDeque.length;
        }
    }

    @Override
    public double result() {
        if (head == tail) {
            return Double.NaN; // Empty window
        }
        return maxDeque[head];
    }
}
