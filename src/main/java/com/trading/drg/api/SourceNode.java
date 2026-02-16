package com.trading.drg.api;

/**
 * Represents a root node in the graph that receives external updates.
 *
 * Source nodes are the entry points for data into the graph. They do not depend
 * on
 * other nodes. Examples include:
 * - Market data updates (prices, volumes).
 * - Configuration parameters (volatility overrides, interest rates).
 * - User inputs or simulation scenarios.
 *
 * Usage Contract (Critical):
 * Updating a source node is a two-step process:
 *
 * 1. Modify State: Call update(value) to change the internal state of this
 * node.
 * 2. Notify Engine: You MUST explicitly notify the StabilizationEngine that
 * this node
 * is "dirty" by calling StabilizationEngine.markDirty(nodeId).
 *
 * If you fail to notify the engine, the change will effectively be invisible.
 * The engine
 * will not visit this node or its dependents during the next stabilization
 * cycle.
 *
 * @param <T> The type of value accepted by this source node.
 */
public interface SourceNode<T> extends Node<T> {

    /**
     * Updates the value of this source node.
     *
     * This operation updates the internal state. It is typically thread-safe in the
     * sense that it can be called from the Producer thread, but the value is only
     * consumed by the specific Consumer thread running the engine.
     *
     * Reminder:
     * Simply calling this method is NOT enough to trigger a graph recalculation.
     * You must also register the node as dirty with the engine.
     *
     * @param value The new value to set.
     */
    void update(T value);
}
