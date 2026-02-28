package com.trading.drg.fn;

/**
 * Functional interface for a scalar computation with 2 inputs.
 */
@FunctionalInterface
public interface Fn2 {
    /**
     * Applies the function.
     * 
     * @param a First input.
     * @param b Second input.
     * @return The result.
     */
    double apply(double a, double b);
}
