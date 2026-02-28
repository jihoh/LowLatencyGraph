package com.trading.drg.fn;

/**
 * Functional interface for a scalar computation with 1 input.
 */
@FunctionalInterface
public interface Fn1 {
    /**
     * Applies the function.
     * 
     * @param a The input value.
     * @return The result.
     */
    double apply(double a);
}
