package com.trading.drg.api;

/**
 * Specialized cutoff strategy for primitive doubles to avoid boxing overhead.
 * Determines if a value change is significant enough to propagate downstream.
 */
@FunctionalInterface
public interface ScalarCutoff {

    /**
     * Determines if the double value has changed enough to warrant propagation.
     *
     * @param previous The value from the previous stabilization cycle.
     * @param current  The newly computed value.
     * @return {@code true} if the change is significant; {@code false} otherwise.
     */
    boolean hasChanged(double previous, double current);
}
