package com.trading.drg.api;

/**
 * Interface for nodes exposing an indexed array of double values (vectors).
 * <p>
 * Enables a zero-copy "View" pattern for low-latency random access
 * without materializing or defensively copying full arrays.
 */
public interface VectorValue extends Node {

    /**
     * Returns the primitive double value at the specified zero-based index.
     *
     * @param index 0-based index of the element to retrieve.
     * @return The primitive double value at that index.
     */
    double valueAt(int index);

    /**
     * Returns the size of the vector.
     *
     * @return The number of elements in the vector.
     */
    int size();

    /**
     * Returns optional string headers (labels) for the vector elements.
     *
     * @return Array of string labels, or null if no headers are defined.
     */
    default String[] headers() {
        return null;
    }
}
