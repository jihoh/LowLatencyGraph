package com.trading.drg.node;

import com.trading.drg.core.Node;
import com.trading.drg.core.VectorReadable;

/**
 * Abstract base class for nodes that produce a fixed-size array of doubles.
 *
 * <p>
 * Ideal for representing:
 * <ul>
 * <li>Yield Curves (e.g., discount factors at 1M, 3M, 6M...).</li>
 * <li>Volatility Surface Slices.</li>
 * <li>Bucketized Risks.</li>
 * </ul>
 *
 * <h3>Zero-Allocation Policy</h3>
 * Unlike standard Java code which might return a new {@code double[]} on every
 * call,
 * {@code VectorNode} maintains two pre-allocated arrays: {@code currentValues}
 * and {@code previousValues}.
 * Logic writes directly into these arrays, avoiding GC pressure.
 */
public abstract class VectorNode implements Node<double[]>, VectorReadable {
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

        // Compute new values directly into the buffer
        compute(currentValues);

        // Check for changes element-wise
        for (int i = 0; i < size; i++)
            if (Math.abs(currentValues[i] - previousValues[i]) > tolerance)
                return true;
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
