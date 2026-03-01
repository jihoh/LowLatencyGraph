package com.trading.drg.node;

import com.trading.drg.api.ScalarCutoff;
import com.trading.drg.api.ScalarValue;

/**
 * Abstract base class for nodes that produce a single double value.
 */
public abstract class ScalarNode implements ScalarValue {
    private final String name;
    private final ScalarCutoff cutoff;

    // Primitive fields for zero-allocation state
    private double currentValue = Double.NaN;
    private double previousValue = Double.NaN;

    protected ScalarNode(String name, ScalarCutoff cutoff) {
        this.name = name;
        this.cutoff = cutoff;
    }

    @Override
    public final String name() {
        return name;
    }

    /** Computes and returns the new value. */
    protected abstract double compute();

    @Override
    public final boolean stabilize() {
        previousValue = currentValue;
        try {
            currentValue = compute();
        } catch (Throwable t) {
            currentValue = Double.NaN;
        }

        if (Double.isNaN(previousValue) != Double.isNaN(currentValue))
            return true;

        return cutoff.hasChanged(previousValue, currentValue);
    }

    @Override
    public final double value() {
        return currentValue;
    }

    /** Returns the value from the previous stabilization cycle. */
    public final double previousValue() {
        return previousValue;
    }
}
