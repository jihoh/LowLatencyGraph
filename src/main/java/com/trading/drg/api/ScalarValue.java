package com.trading.drg.api;

/**
 * Interface for nodes that expose a primitive double value,
 * enabling zero-GC performance in numeric calculations by avoiding autoboxing.
 */
public interface ScalarValue extends Node {

    /**
     * Returns the current stabilized value as a primitive double.
     *
     * @return The primitive double value of the node.
     */
    double value();
}
