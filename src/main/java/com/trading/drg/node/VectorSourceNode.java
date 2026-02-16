package com.trading.drg.node;

import com.trading.drg.core.SourceNode;
import com.trading.drg.core.VectorReadable;

/**
 * A source node for array-based data.
 *
 * <p>
 * Allows feeding entire curves or vectors into the graph efficiently.
 * Supports partial updates via {@link #updateAt(int, double)}.
 */
public final class VectorSourceNode implements SourceNode<double[]>, VectorReadable {
    private final String name;
    private final double tolerance;
    private final int size;
    private final double[] currentValues;
    private final double[] previousValues;
    private boolean initialized = false;
    private boolean dirty;

    public VectorSourceNode(String name, int size, double tolerance) {
        this.name = name;
        this.tolerance = tolerance;
        this.size = size;
        this.currentValues = new double[size];
        this.previousValues = new double[size];
        // previousValues initialized to 0.0 by default, strict initialization handled
        // by flag
    }

    /**
     * Creates a vector source with default tolerance (1e-15).
     */
    public VectorSourceNode(String name, int size) {
        this(name, size, 1e-15);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void update(double[] values) {
        if (values.length != size) {
            throw new IllegalArgumentException(
                    "Vector length mismatch: expected " + size + ", got " + values.length + " for node: " + name);
        }
        for (double v : values) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("Invalid value in vector update: " + v + " for node: " + name);
            }
        }
        // Only update current values. Do NOT touch previousValues until stabilize()
        System.arraycopy(values, 0, currentValues, 0, size);
        dirty = true;
    }

    /**
     * Update a single element of the vector.
     * Useful for spot shocks to a curve point.
     */
    public void updateAt(int index, double value) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "Index out of bounds: " + index + " (size=" + size + ") for node: " + name);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "Invalid value: " + value + " for node: " + name + " at index: " + index);
        }
        currentValues[index] = value;
        dirty = true;
    }

    @Override
    public boolean stabilize() {
        // Check for initialization (first run)
        if (!initialized) {
            System.arraycopy(currentValues, 0, previousValues, 0, size);
            initialized = true;
            return true;
        }

        // Check for changes
        boolean changed = false;
        for (int i = 0; i < size; i++) {
            if (Math.abs(currentValues[i] - previousValues[i]) > tolerance) {
                changed = true;
                break;
            }
        }

        if (changed) {
            // Commit new state
            System.arraycopy(currentValues, 0, previousValues, 0, size);
        }
        return changed;
    }

    @Override
    public double[] value() {
        return currentValues;
    }

    @Override
    public double valueAt(int index) {
        return currentValues[index];
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
