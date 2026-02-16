package com.trading.drg.fn;

/**
 * Functional interface for a scalar computation with 1 input.
 *
 * <p>
 * Used by
 * {@link com.trading.graph.GraphBuilder#compute(String, Fn1, com.trading.drg.core.DoubleValue)}
 * to define nodes that transform a single double value into another double
 * value.
 *
 * <p>
 * Examples:
 * <ul>
 * <li>{@code x -> x * 2.0}</li>
 * <li>{@code Math::log}</li>
 * </ul>
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
