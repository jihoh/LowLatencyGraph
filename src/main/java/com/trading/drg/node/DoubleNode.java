package com.trading.drg.node;

import com.trading.drg.api.*;

import com.trading.drg.api.DoubleCutoff;
import com.trading.drg.api.DoubleValue;

/**
 * Abstract base class for nodes that produce a single double value.
 *
 * This class implements the DoubleValue interface to allow zero-boxing access
 * to its value. It also handles the boilerplate of state management (current vs
 * previous value) and change detection via DoubleCutoff.
 *
 * Design:
 * - Template Method: The stabilize() method is final and implements the change,
 * detection logic. Subclasses only need to implement the compute() method.
 * - Zero Allocation: Stores state in primitive double fields to avoid object
 * creation during updates.
 *
 * Subclassing:
 * Implement compute() to define how the node calculates its new value based on
 * its inputs.
 */
public abstract class DoubleNode implements DoubleValue {
    private final String name;
    private final DoubleCutoff cutoff;

    // Primitive fields for zero-allocation state
    private double currentValue = Double.NaN;
    private double previousValue = Double.NaN;

    protected DoubleNode(String name, DoubleCutoff cutoff) {
        this.name = name;
        this.cutoff = cutoff;
    }

    @Override
    public final String name() {
        return name;
    }

    /**
     * Internal compute method.
     * 
     * @return The new calculated value.
     */
    protected abstract double compute();

    @Override
    public final boolean stabilize() {
        previousValue = currentValue;
        currentValue = compute();

        // Always propagate if the previous value was NaN (initialization)
        if (Double.isNaN(previousValue))
            return true;

        return cutoff.hasChanged(previousValue, currentValue);
    }

    @Override
    public final Double value() {
        return currentValue;
    }

    @Override
    public final double doubleValue() {
        return currentValue;
    }

    /**
     * Returns the value from the previous stabilization cycle.
     * Useful for debugging or delta calculations.
     */
    public final double previousDoubleValue() {
        return previousValue;
    }
}
