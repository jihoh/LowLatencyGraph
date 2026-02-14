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
 * When a source node is updated via {@link #update(Object)}, it marks itself as
 * "dirty".
 * The {@link StabilizationEngine} will then propagate this change to all
 * dependent nodes
 * during the next stabilization cycle.
 *
 * @param <T> The type of value accepted by this source node.
 */
public interface SourceNode<T> extends Node<T> {

    /**
     * Updates the value of this source node.
     *
     * <p>
     * This operation should be efficient and typically sets a "dirty" flag.
     * It does <b>not</b> trigger propagation immediately; propagation occurs when
     * {@link StabilizationEngine#stabilize()} is called.
     *
     * @param value The new value.
     */
    void update(T value);

    /**
     * Checks if this node has been updated since the last stabilization.
     *
     * @return {@code true} if the node has new data; {@code false} otherwise.
     */
    boolean isDirty();

    /**
     * Clears the dirty flag.
     *
     * <p>
     * This is called by the {@link StabilizationEngine} after the node's change
     * has been successfully propagated to its direct dependents.
     */
    void clearDirty();
}
