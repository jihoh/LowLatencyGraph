package com.trading.drg.node;

import com.trading.drg.api.VectorValue;

/**
 * Abstract base class for nodes that produce a fixed-size array of doubles.
 *
 * Ideal for representing:
 * - Yield Curves (e.g., discount factors at 1M, 3M, 6M...).
 * - Volatility Surface Slices.
 * - Bucketized Risks (e.g., Delta by tenor).
 *
 * Zero-Allocation Policy:
 * Unlike standard Java code which might return a new double[] on every call,
 * VectorNode maintains two pre-allocated arrays: currentValues and
 * previousValues.
 *
 * 1. Logic writes directly into these arrays, avoiding GC pressure.
 * 2. Downstream nodes can read directly from these arrays via valueAt(i).
 * 3. Stabilization uses System.arraycopy for fast state management.
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

    /**
     * Subclasses implement this to populate the output array.
     * 
     * @param output The array to write results into.
     */
    protected abstract void compute(double[] output);

    @Override
    public final boolean stabilize() {
        // Swap or copy? Here we copy to preserve history for delta check.
        // System.arraycopy is intrinsic and extremely fast.
        System.arraycopy(currentValues, 0, previousValues, 0, size);

        try {
            // Compute new values directly into the buffer
            compute(currentValues);
        } catch (Throwable t) {
            java.util.Arrays.fill(currentValues, Double.NaN);
            throw t;
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
    public final double[] value() {
        return currentValues;
    }

    @Override
    public final double valueAt(int index) {
        return currentValues[index];
    }

    public final double previousValueAt(int index) {
        return previousValues[index];
    }
}
