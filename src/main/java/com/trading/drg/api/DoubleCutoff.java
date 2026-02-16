package com.trading.drg.api;

/**
 * Specialized Cutoff strategy for primitive doubles.
 *
 * A "Cutoff" determines when a change in value is significant enough to
 * propagate
 * downstream. If the change is insignificant (e.g., floating point noise), we
 * return
 * false, stopping the graph update for that branch.
 *
 * Performance:
 * This interface works with primitive 'double' values to avoid boxing overhead
 * during
 * difference checks.
 *
 * Common Implementations:
 * - Exact: Returns true if prev != curr. (Note: Handles NaN comparisons via
 * Double.compare logic if needed).
 * - Epsilon: Returns true if Math.abs(prev - curr) > epsilon. Useful for
 * ignoring micro-changes.
 */
@FunctionalInterface
public interface DoubleCutoff {

    /**
     * Determines if the double value has changed enough to warrant propagation.
     *
     * @param previous The value from the previous stabilization cycle.
     * @param current  The newly computed value.
     * @return true if the change is significant and should dirty dependent nodes;
     *         false otherwise.
     */
    boolean hasChanged(double previous, double current);
}
