package com.trading.drg.fn.finance;

/**
 * Calculates the raw non-commutative difference between two streams.
 * 
 * y = minuend - subtrahend
 */
public class Spread extends AbstractFn2 {

    @Override
    protected double calculate(double a, double b) {
        return a - b;
    }

}
