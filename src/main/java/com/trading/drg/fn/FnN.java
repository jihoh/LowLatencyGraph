package com.trading.drg.fn;

/**
 * Functional interface for a scalar computation with N inputs.
 *
 * <p>
 * To avoid garbage collection, the input array is often a pre-allocated scratch
 * buffer
 * that is reused across invocations. Implementations must <b>not</b> capture or
 * store the reference
 * to the {@code inputs} array, as its contents will change.
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
