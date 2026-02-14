package com.trading.drg.core;

/**
 * Strategy interface for determining if a node's value has meaningfully
 * changed.
 *
 * <p>
 * The "Cutoff" concept is central to the efficiency of incremental computation.
 * Even if a node recomputes, if the result is effectively the same as before
 * (e.g., within a tolerance),
 * we can stop propagating updates to downstream nodes. This prunes the graph
 * traversal significantly.
 *
 * @param <T> The type of value being compared.
 */
@FunctionalInterface
public interface Cutoff<T> {

    /**
     * Determines if the value has changed enough to warrant propagation.
     *
     * @param previous The previous stabilized value.
     * @param current  The newly computed value.
     * @return {@code true} if the change is significant; {@code false} otherwise.
     */
    boolean hasChanged(T previous, T current);
}
