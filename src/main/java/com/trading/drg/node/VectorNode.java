package com.trading.drg.node;

import com.trading.drg.api.VectorValue;

/**
 * Abstract base class for nodes that produce a fixed-size array of doubles.
 * Maintains pre-allocated arrays to guarantee a zero-allocation update policy.
 */
public abstract class VectorNode implements VectorValue {
    private final String name;
    private final double tolerance;
    private final double[] currentValues;
    private final double[] previousValues;
    private final int size;

    protected VectorNode(String name, int size, double tolerance) {
        this.name = name;
        this.tolerance = tolerance;
        this.size = size;
        this.currentValues = new double[size];
        this.previousValues = new double[size];
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final int size() {
        return size;
    }

    /** Computes the vector values directly into the output array. */
    protected abstract void compute(double[] output);

    @Override
    public final boolean stabilize() {
        System.arraycopy(currentValues, 0, previousValues, 0, size);

        try {
            compute(currentValues);
        } catch (Throwable t) {
            java.util.Arrays.fill(currentValues, Double.NaN);
        }

        // Check for changes element-wise
        for (int i = 0; i < size; i++) {
            double c = currentValues[i];
            double p = previousValues[i];
            if (Double.isNaN(p) != Double.isNaN(c) || Math.abs(c - p) > tolerance)
                return true;
        }
        return false;
    }

    @Override
    public final double valueAt(int index) {
        return currentValues[index];
    }

    public final double previousValueAt(int index) {
        return previousValues[index];
    }
}
