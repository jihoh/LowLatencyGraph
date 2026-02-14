package com.trading.drg.core;

/**
 * Interface for nodes that expose an indexed array of double values (vectors).
 *
 * <p>
 * This is used for representing curves (e.g., Yield Curves, Volatility Surfaces
 * slices)
 * or any collection of numeric data that needs to be processed efficiently.
 *
 * <h3>Optimization</h3>
 * Instead of returning a {@code double[]} array which might require copying,
 * this interface
 * allows for random access via {@link #valueAt(int)}. This enables "zero-copy"
 * views
 * where a downstream node can read a specific element without materializing the
 * whole vector.
 */
public interface VectorReadable {

    /**
     * Returns the value at the specified index.
     *
     * @param index 0-based index.
     * @return The primitive double value at that index.
     * @throws IndexOutOfBoundsException if index is invalid (implementation
     *                                   dependent).
     */
    double valueAt(int index);

    /**
     * Returns the size of the vector.
     *
     * @return The number of elements.
     */
    int size();
}
