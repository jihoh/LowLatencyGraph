package com.trading.drg.api;

/**
 * Interface for nodes that expose an indexed array of double values (vectors).
 *
 * This is used for representing curves (e.g., Yield Curves, Volatility Surfaces
 * slices)
 * or any collection of numeric data that needs to be processed efficiently
 * (e.g., a
 * collection of shock scenarios).
 *
 * Performance Optimization:
 * Instead of forcing the node to return a 'double[]' array object (which might
 * require
 * defensive copying to ensure immutability), this interface enables a "View"
 * pattern.
 *
 * 1. Random Access: Downstream nodes can call valueAt(int) to read specific
 * elements
 * without materializing the entire vector.
 * 2. Zero-Copy: If the vector is backed by shared memory or a reusable buffer,
 * this
 * interface allows reading directly from that source without object allocation.
 *
 * This is essential for handling large vectors in a low-latency environment
 * where
 * allocation and copying would be prohibitive.
 */
public interface VectorValue extends Node<double[]> {

    /**
     * Returns the value at the specified index.
     *
     * This provides zero-allocation random access to the vector elements.
     *
     * @param index 0-based index of the element to retrieve.
     * @return The primitive double value at that index.
     * @throws IndexOutOfBoundsException if index is invalid (implementation
     *                                   dependent).
     */
    double valueAt(int index);

    /**
     * Returns the size of the vector.
     *
     * Useful for iterating over the vector elements.
     *
     * @return The number of elements in the vector.
     */
    int size();
}
