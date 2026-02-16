package com.trading.drg.api;

/**
 * A node in the dependency graph.
 *
 * This interface represents the fundamental unit of computation in the
 * ClaudeGraph engine.
 * Every node -- whether it's a source of data, a calculation, or an output sink
 * -- implements
 * this interface.
 *
 * Key Responsibilities:
 *
 * 1. Identity: Every node has a unique name within the graph. This is used for
 * wiring,
 * debugging, and looking up nodes in the GraphContext.
 *
 * 2. Computation: The stabilize() method contains the logic to update the
 * node's state.
 * It is the heart of the reactive propagation mechanism.
 *
 * 3. State Access: The value() method provides access to the current result.
 *
 * Performance Note:
 * This interface is generic to allow flexibility, but for high-performance
 * numeric paths,
 * refer to specialized sub-interfaces like DoubleValue and VectorValue. These
 * avoid the
 * overhead of boxing primitive values into Objects (e.g., double to Double),
 * which is critical
 * for zero-GC execution in hot paths.
 *
 * @param <T> The type of value held by this node (e.g., Double, double[],
 *            Boolean).
 */
public interface Node<T> {

    /**
     * Returns the unique name of this node.
     *
     * Node names are used for wiring dependencies during graph construction,
     * looking up
     * nodes in the GraphContext, and for debugging/visualization purposes.
     *
     * @return The unique identifier for this node.
     */
    String name();

    /**
     * Recomputes the node's value based on its inputs.
     *
     * This method is called by the StabilizationEngine during a stabilization pass
     * if the
     * node has been marked as "dirty" (i.e., one of its dependencies has changed).
     *
     * Change Detection:
     * The implementation must compare the newly computed value with the previous
     * value
     * using an appropriate Cutoff strategy (e.g., DoubleCutoff) to determine if a
     * meaningful
     * change occurred.
     *
     * Return Value Contract:
     * - Returns true: The value changed significantly. The engine will propagate
     * this change
     * to all downstream dependents, marking them as dirty.
     * - Returns false: The value is effectively unchanged. The engine will stop
     * propagation
     * along this branch, saving CPU cycles.
     *
     * @return true if the output value has changed meaningfully such that
     *         downstream
     *         dependents need to be updated; false otherwise.
     */
    boolean stabilize();

    /**
     * Returns the current value of the node.
     *
     * Consistency Warning:
     * This value is only guaranteed to be consistent after the StabilizationEngine
     * has
     * completed a stabilization pass. Accessing it during stabilization (e.g., from
     * another
     * thread or before the pass is complete) may yield undefined, inconsistent, or
     * tear-prone results.
     *
     * @return The current stabilized state of the node.
     */
    T value();
}
