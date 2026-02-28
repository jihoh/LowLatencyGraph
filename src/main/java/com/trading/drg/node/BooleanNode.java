package com.trading.drg.node;

import com.trading.drg.api.Node;

/**
 * Boolean signal node that propagates only when its value flips.
 * Designed to act as a trigger mechanism within the graph.
 */
public final class BooleanNode implements Node {
    private final String name;
    private final BooleanCalcFn fn;

    // State storage
    private boolean currentValue;
    private boolean previousValue;

    // Efficiency flag: track if we've ever stabilized to handle the "first run"
    // edge case.
    private boolean initialized;

    /**
     * Creates a new BooleanNode.
     *
     * @param name The unique name of this node in the graph.
     * @param fn   The calculation function that returns a boolean.
     */
    public BooleanNode(String name, BooleanCalcFn fn) {
        this.name = name;
        this.fn = fn;
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Recomputes the node's value and determines if it has changed.
     *
     * @return {@code true} if the value flipped or on first run, {@code false}
     *         otherwise.
     */
    @Override
    public boolean stabilize() {
        // 1. Snapshot previous state for comparison
        previousValue = currentValue;

        // 2. Execute the actual logic (lambda)
        currentValue = fn.compute();

        // 3. Handle initialization edge case
        // On the very first run, we must return 'true' (changed) so downstream nodes
        // get a chance to see the initial value, even if that value happens to be
        // 'false'
        // (matching the default field value).
        if (!initialized) {
            initialized = true;
            return true;
        }

        // 4. Standard change detection: strictly equals check (XOR logic effectively)
        return currentValue != previousValue;
    }

    /** @return the current primitive boolean value. */
    public boolean booleanValue() {
        return currentValue;
    }

    /** Functional interface for calculation logic. */
    @FunctionalInterface
    public interface BooleanCalcFn {
        boolean compute();
    }
}
