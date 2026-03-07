package com.trading.drg.api;

/**
 * Defines the math for calculating O(1) rolling statistics over a sliding window.
 * This acts as the state machine plug-in for {@link com.trading.drg.node.WindowedNode}.
 */
public interface WindowedAccumulator {
    
    /**
     * Called when a new value enters the sliding window.
     * @param value the new data point
     */
    void onAdd(double value);
    
    /**
     * Called when the oldest value leaves the sliding window.
     * @param value the data point falling out of the window
     */
    void onRemove(double value);
    
    /**
     * Returns the current computed aggregate of the window.
     * @return the result
     */
    double result();
}
