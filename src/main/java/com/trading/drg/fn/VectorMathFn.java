package com.trading.drg.fn;

/**
 * Functional interface for a vector computation with N inputs.
 * <p>
 * Note: The input array is a reused scratch buffer; do not store its reference.
 */
@FunctionalInterface
public interface VectorMathFn {
    /**
     * Computes a vector result from an array of inputs.
     * 
     * @param inputs The input values (read-only, transient).
     * @param output The pre-allocated output buffer to write results into.
     */
    void apply(double[] inputs, double[] output);
}
