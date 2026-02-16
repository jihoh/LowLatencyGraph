package com.trading.drg.api;

/**
 * Interface for nodes that expose a primitive double value.
 *
 * <p>
 * This interface is a critical performance optimization. By implementing
 * {@code DoubleValue},
 * a node allows downstream consumers to access its value without the overhead
 * of boxing/unboxing
 * to {@code Double} objects.
 *
 * <p>
 * Most numeric calculation nodes (e.g., standard arithmetic, pricing models)
 * should implement this.
 */
public interface DoubleValue extends Node<Double> {

    /**
     * Returns the current value as a primitive double.
     *
     * @return Similarly to {@link Node#value()}, this returns the stabilized state
     *         of the node.
     */
    double doubleValue();
}
