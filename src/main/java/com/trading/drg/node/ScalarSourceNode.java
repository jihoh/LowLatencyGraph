package com.trading.drg.node;

import com.trading.drg.api.ScalarCutoff;
import com.trading.drg.api.ScalarValue;
import com.trading.drg.api.SourceNode;
import com.trading.drg.util.ScalarCutoffs;

/**
 * A source node acting as an input for double values.
 *
 * Used for:
 * - Market Data (e.g., "Mkt:AAPL_Price")
 * - Model Parameters (e.g., "Param:RiskFreeRate")
 * - Scenario Overrides
 *
 * Dirty Flag Contract:
 * This node maintains a "dirty" flag concept indirectly via the Engine's dirty
 * array.
 * When update(value) is called, the caller must ALSO call
 * engine.markDirty(nodeId)
 * to ensure the new value is picked up during the next stabilization pass.
 */
public final class ScalarSourceNode implements SourceNode<Double>, ScalarValue {
    private final String name;
    private final ScalarCutoff cutoff;
    private double currentValue;
    private double previousValue = Double.NaN;

    /**
     * Creates a named source node with a custom cutoff strategy.
     * 
     * @param name         Unique node name.
     * @param initialValue Initial value (defaults to NaN if not provided).
     * @param cutoff       Strategy to determine if updates are meaningful.
     */
    public ScalarSourceNode(String name, double initialValue, ScalarCutoff cutoff) {
        this.name = name;
        this.cutoff = cutoff;
        this.currentValue = initialValue;
    }

    /**
     * Creates a named source node with EXACT change detection.
     */
    public ScalarSourceNode(String name, double initialValue) {
        this(name, initialValue, ScalarCutoffs.EXACT);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void update(Double value) {
        updateDouble(value);
    }

    /**
     * Specialized update method for primitive doubles.
     * Sets the dirty flag to ensure propagation during next stabilization.
     */
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
    public Double value() {
        return currentValue;
    }

    @Override
    public double doubleValue() {
        return currentValue;
    }

    public double previousDoubleValue() {
        return previousValue;
    }
}
