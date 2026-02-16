package com.trading.drg.api;

/**
 * A node in the dependency graph.
 *
 * <p>
 * This interface represents the fundamental unit of computation in the
 * ClaudeGraph engine.
 * Every node — whether it's a source of data, a calculation, or an output sink
 * — implements this interface.
 *
 * <h3>Key Responsibilities</h3>
 * <ul>
 * <li><b>Identity:</b> Every node has a unique name within the graph.</li>
 * <li><b>Computation:</b> The {@link #stabilize()} method contains the logic to
 * update the node's state.</li>
 * <li><b>State Access:</b> The {@link #value()} method provides access to the
 * current result.</li>
 * </ul>
 *
 * @param <T> The type of value held by this node (e.g., Double, double[],
 *            Boolean).
 */
public interface Node<T> {

    /**
     * Returns the unique name of this node.
     *
     * <p>
     * Node names are used for:
     * <ul>
     * <li>Wiring dependencies during graph construction.</li>
     * <li>Looking up nodes in the {@link GraphContext}.</li>
     * <li>Debugging and visualization.</li>
     * </ul>
     *
     * @return The unique identifier for this node.
     */
    String name();

    /**
     * Recomputes the node's value based on its inputs.
     *
     * <p>
     * This method is called by the {@link StabilizationEngine} during a
     * stabilization pass
     * if the node has been marked as "dirty" (i.e., one of its dependencies has
     * changed).
     *
     * <h3>Change Detection</h3>
     * The implementation must compare the newly computed value with the previous
     * value
     * (using an appropriate {@link Cutoff} strategy) to determine if a meaningful
     * change occurred.
     *
     * @return {@code true} if the output value has changed meaningfully such that
     *         downstream
     *         dependents need to be updated; {@code false} otherwise.
     */
    boolean stabilize();

    /**
     * param Returns the current value of the node.
     *
     * <p>
     * This value is only guaranteed to be consistent after the
     * {@link StabilizationEngine}
     * has completed a stabilization pass. Accessing it during stabilization (e.g.,
     * from another thread)
     * may yield undefined or tear-prone results.
     *
     * @return The current state of the node.
     */
    T value();
}
