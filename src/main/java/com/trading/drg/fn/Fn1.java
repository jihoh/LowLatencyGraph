package com.trading.drg.fn;

/**
 * Functional interface for a scalar computation with 1 input.
 *
 * Used by GraphBuilder.compute to define nodes that transform a single double
 * value
 * into another double value.
 *
 * Examples:
 * - x -> x * 2.0
 * - Math::log
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
