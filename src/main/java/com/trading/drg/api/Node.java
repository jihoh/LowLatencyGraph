package com.trading.drg.api;

/**
 * A node in the dependency graph.
 *
 * <p>
 * Nodes are the fundamental unit of computation, providing identity,
 * optional state, and a re-evaluation mechanism via {@link #stabilize()}.
 *
 * @param <T> The type of value held by this node.
 */
public interface Node {

    /**
     * Returns the unique name of this node.
     *
     * @return The unique identifier for this node.
     */
    String name();

    /**
     * Recomputes the node's value based on its inputs.
     *
     * @return {@code true} if the output value changed meaningfully,
     *         requiring downstream dependents to update; {@code false} otherwise.
     */
    boolean stabilize();
}
