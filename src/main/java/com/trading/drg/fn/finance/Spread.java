package com.trading.drg.fn.finance;

/**
 * Difference between two streams.
 * <p>
 * Formula: {@code y = a - b}
 */
public class Spread extends AbstractFn2 {

    @Override
    protected double calculate(double a, double b) {
        return a - b;
    }

}
