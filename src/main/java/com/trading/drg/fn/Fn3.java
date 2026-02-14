package com.trading.drg.fn;

/**
 * Functional interface for a scalar computation with 3 inputs.
 */
@FunctionalInterface
public interface Fn3 {
    /**
     * Applies the function.
     * 
     * @param a First input.
     * @param b Second input.
     * @param c Third input.
     * @return The result.
     */
    double apply(double a, double b, double c);
}
