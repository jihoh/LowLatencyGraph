package com.trading.drg.core;

/**
 * Represents a root node in the graph that receives external updates.
 *
 * <p>
 * Source nodes are the entry points for data into the graph. Examples include:
 * <ul>
 * <li>Market data updates (prices, volumes).</li>
 * <li>Configuration parameters (volatility overrides, interest rates).</li>
 * <li>User inputs or simulation scenarios.</li>
 * </ul>
 *
 * <h3>Update Contract</h3>
 * When a source node is updated via {@link #update(Object)}, the engine must be
 * explicitly notified via {@link StabilizationEngine#markDirty(String)}.
 * The {@link StabilizationEngine} will then propagate this change to all
 * dependent nodes during the next stabilization cycle.
 *
 * @param <T> The type of value accepted by this source node.
 */
public interface SourceNode<T> extends Node<T> {

    /**
     * Updates the value of this source node.
     *
     * <p>
     * This operation updates the internal state.
     * <b>Crucial:</b> The caller MUST separately notify the
     * {@link StabilizationEngine}
     * via {@link StabilizationEngine#markDirty(String)} or similar for the change
     * to propagate.
     *
     * @param value The new value.
     */
    void update(T value);
}
