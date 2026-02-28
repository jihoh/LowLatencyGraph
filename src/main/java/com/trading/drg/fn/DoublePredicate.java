package com.trading.drg.fn;

/**
 * Functional interface for testing a double value. Used for conditional nodes.
 */
@FunctionalInterface
public interface DoublePredicate {
    /**
     * Evaluates the predicate.
     * 
     * @param value The input value.
     * @return {@code true} if the input matches the criteria.
     */
    boolean test(double value);
}
