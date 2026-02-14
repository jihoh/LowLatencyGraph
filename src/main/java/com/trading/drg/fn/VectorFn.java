package com.trading.drg.fn;

import com.trading.drg.core.Node;

/**
 * Functional interface for vector computations.
 *
 * <p>
 * Calculates an array of doubles based on a set of input nodes.
 * The output array is pre-allocated by the framework and passed in, allowing
 * for zero-allocation results.
 */
@FunctionalInterface
public interface VectorFn {
    /**
     * Computes the vector output.
     *
     * @param inputs The input nodes (dependencies). Implementations should cast
     *               these to expected types.
     * @param output The pre-allocated output buffer to write results into.
     */
    void compute(Node<?>[] inputs, double[] output);
}
