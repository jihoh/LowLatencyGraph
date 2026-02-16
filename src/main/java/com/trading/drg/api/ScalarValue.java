package com.trading.drg.api;

/**
 * Interface for nodes that expose a primitive double value.
 *
 * Performance Optimization:
 * This interface is critical for achieving zero-GC performance in numeric
 * calculations.
 * By implementing DoubleValue, a node allows downstream consumers to access its
 * result
 * directly as a primitive 'double'.
 *
 * This avoids the significant overhead of:
 * 1. Auto-boxing the result into a Double object.
 * 2. Creating garbage that eventually triggers Graph Collection (GC) pauses.
 * 3. Pointer indirection when reading the value.
 *
 * Usage:
 * Most numeric calculation nodes (e.g., standard arithmetic, pricing models,
 * spreads)
 * should implement this interface instead of just Node<Double>. Downstream
 * nodes should
 * check for this interface and call doubleValue() instead of value().
 */
public interface ScalarValue extends Node<Double> {

    /**
     * Returns the current value as a primitive double.
     *
     * Similar to Node.value(), this returns the stabilized state of the node.
     * It allows access without boxing.
     *
     * @return The primitive double value of the node.
     */
    double doubleValue();
}
