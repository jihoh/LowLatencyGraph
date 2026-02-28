package com.trading.drg.fn.finance;

/**
 * Arbitrage spread between a Direct rate and a Synthetic rate.
 * <p>
 * Formula: {@code Spread = Direct - (Leg1 * Leg2)}
 */
public class TriangularArbSpread extends AbstractFn3 {

    @Override
    protected double calculate(double leg1, double leg2, double direct) {
        double synthetic = leg1 * leg2;
        return direct - synthetic;
    }

}
