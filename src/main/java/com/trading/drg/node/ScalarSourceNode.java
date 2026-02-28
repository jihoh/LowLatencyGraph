package com.trading.drg.node;

import com.trading.drg.api.ScalarCutoff;
import com.trading.drg.api.ScalarValue;
import com.trading.drg.api.SourceNode;
import com.trading.drg.util.ScalarCutoffs;

/**
 * A source node acting as an input for scalar double values.
 */
public final class ScalarSourceNode implements SourceNode, ScalarValue {
    private final String name;
    private final ScalarCutoff cutoff;
    private double currentValue;
    private double previousValue = Double.NaN;

    /** Creates a named source node with a custom cutoff strategy. */
    public ScalarSourceNode(String name, double initialValue, ScalarCutoff cutoff) {
        this.name = name;
        this.cutoff = cutoff;
        this.currentValue = initialValue;
    }

    /** Creates a named source node with exact change detection. */
    public ScalarSourceNode(String name, double initialValue) {
        this(name, initialValue, ScalarCutoffs.EXACT);
    }

    @Override
    public String name() {
        return name;
    }

    public void update(Double value) {
        updateDouble(value);
    }

    /** Updates the primitive double value directly. */
    public void updateDouble(double value) {
        this.currentValue = value;
    }

    @Override
    public boolean stabilize() {
        double prev = previousValue;
        previousValue = currentValue;

        // First run: always propagate
        if (Double.isNaN(prev)) {
            return true;
        }

        return cutoff.hasChanged(prev, currentValue);
    }

    @Override
    public double value() {
        return currentValue;
    }

    public double previousValue() {
        return previousValue;
    }
}
