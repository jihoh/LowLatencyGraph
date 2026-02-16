package com.trading.drg.fn;

/**
 * Functional interface for a scalar computation with 2 inputs.
 *
 * <p>
 * Used by
 * {@link com.trading.graph.GraphBuilder#compute(String, Fn2, com.trading.drg.core.DoubleValue, com.trading.drg.core.DoubleValue)}.
 *
 * <p>
 * Examples:
 * <ul>
 * <li>{@code (a, b) -> a + b}</li>
 * <li>{@code Math::pow}</li>
 * </ul>
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
