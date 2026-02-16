package com.trading.drg.node;

import com.trading.drg.api.*;

import com.trading.drg.api.DoubleCutoff;
import com.trading.drg.api.DoubleReadable;
import com.trading.drg.api.SourceNode;
import com.trading.drg.util.DoubleCutoffs;

/**
 * A source node acting as an input for double values.
 *
 * <p>
 * Used for:
 * <ul>
 * <li>Market Data (e.g., "Mkt:AAPL_Price")</li>
 * <li>Model Parameters (e.g., "Param:RiskFreeRate")</li>
 * <li>Scenario Overrides</li>
 * </ul>
 *
 * <p>
 * This node maintains a "dirty" flag that is set when {@link #update(Double)}
 * is called
 * and cleared after stabilization.
 */
public final class DoubleSourceNode implements SourceNode<Double>, DoubleReadable {
    private final String name;
    private final DoubleCutoff cutoff;
    private double currentValue;
    private double previousValue = Double.NaN;

    /**
     * Creates a named source node with a custom cutoff strategy.
     * 
     * @param name         Unique node name.
     * @param initialValue Initial value (defaults to NaN if not provided).
     * @param cutoff       Strategy to determine if updates are meaningful.
     */
    public DoubleSourceNode(String name, double initialValue, DoubleCutoff cutoff) {
        this.name = name;
        this.cutoff = cutoff;
        this.currentValue = initialValue;
    }

    /**
     * Creates a named source node with EXACT change detection.
     */
    public DoubleSourceNode(String name, double initialValue) {
        this(name, initialValue, DoubleCutoffs.EXACT);
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
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Invalid value: " + value + " for node: " + name);
        }
        this.currentValue = value;
    }

    @Override
    public boolean stabilize() {
        // Source nodes stabilize by checking if their updated value
        // is significantly different from the previous stabilized value.

        // Fix: If previous value is NaN (initial state), always propagate.
        if (Double.isNaN(previousValue)) {
            previousValue = currentValue; // Capture state
            return true;
        }

        // Even if marked dirty, if the value didn't change (e.g. 100.0 -> 100.0),
        // we return false to stop propagation.
        boolean changed = cutoff.hasChanged(previousValue, currentValue);
        if (changed) {
            previousValue = currentValue; // Capture state only on change
        }
        return changed;
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
