package com.trading.drg.node;

import com.trading.drg.core.DoubleCutoff;
import com.trading.drg.core.DoubleReadable;
import com.trading.drg.core.SourceNode;
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
    private double lastStabilizedValue = Double.NaN;
    private boolean dirty;

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
        this.previousValue = this.currentValue;
        this.currentValue = value;
        this.dirty = true;
    }

    @Override
    public boolean stabilize() {
        // Always propagate on first stabilization (lastStabilizedValue is NaN)
        // to ensure downstream nodes are initialized correctly.
        if (Double.isNaN(lastStabilizedValue)) {
            lastStabilizedValue = currentValue;
            return true;
        }
        // Compare against the last stabilized value, not the previous update.
        // This ensures correct cutoff behavior when updateDouble() is called
        // multiple times between stabilizations.
        boolean changed = cutoff.hasChanged(lastStabilizedValue, currentValue);
        if (changed) {
            lastStabilizedValue = currentValue;
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

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void clearDirty() {
        dirty = false;
    }
}
