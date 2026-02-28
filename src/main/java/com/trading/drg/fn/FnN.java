package com.trading.drg.fn;

/**
 * Functional interface for a scalar computation with N inputs.
 * <p>
 * Note: The input array is a reused scratch buffer; do not store its reference.
 */
@FunctionalInterface
public interface FnN {
    /**
     * Computes a result from an array of inputs.
     * 
     * @param inputs The input values (read-only, transient).
     * @return The result.
     */
    double apply(double[] inputs);
}
