package com.trading.drg.node;

import com.trading.drg.api.*;

import com.trading.drg.api.Node;

/**
 * Boolean signal node. Propagates only when value flips.
 *
 * <p>
 * This node is designed to act as a gate or trigger mechanism within the
 * dependency graph.
 * Unlike standard {@link DoubleNode} or {@link VectorNode} which may propagate
 * updates even for small changes
 * (depending on tolerance), the {@code BooleanNode} enforces a strict "change
 * of state" contract.
 *
 * <h3>Propagation Logic</h3>
 * <ul>
 * <li>The node only returns {@code true} from {@link #stabilize()} when its
 * boolean state changes (flip-flop).</li>
 * <li>This behavior is essential for implementing "event-driven" logic within
 * the graph, such as
 * triggering a barrier breach, signal activation, or regime switch.</li>
 * <li>Downstream nodes will only be marked dirty if the boolean condition
 * actually toggles.</li>
 * </ul>
 *
 * <h3>Initialization</h3>
 * <p>
 * The node handles its first stabilization specially: it always returns
 * {@code true} (changed)
 * on the very first pass to ensure downstream consumers are initialized with
 * the correct starting state,
 * regardless of what the default {@code false} state might suggest.
 */
public final class BooleanNode implements Node<Boolean> {
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
     * <p>
     * This method executes the user-provided {@link BooleanCalcFn}.
     * It then compares the new result with the previous result.
     *
     * @return {@code true} if the value has changed (flipped) or if this is the
     *         first stabilization.
     *         {@code false} otherwise.
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

    @Override
    public Boolean value() {
        return currentValue;
    }

    /**
     * Optimized primitive access to avoid unboxing overhead.
     *
     * @return the current primitive boolean value.
     */
    public boolean booleanValue() {
        return currentValue;
    }

    /**
     * Functional interface for the calculation logic.
     */
    @FunctionalInterface
    public interface BooleanCalcFn {
        boolean compute();
    }
}
