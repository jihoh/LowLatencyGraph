package com.trading.drg.fn.finance;

/**
 * Calculates the arbitrage spread between a Direct rate and a Synthetic rate.
 *
 * Spread = Direct - (Leg1 * Leg2)
 *
 * Example: Spread = EUR/JPY - (EUR/USD * USD/JPY)
 */
public class TriangularArbSpread extends AbstractFn3 {

    @Override
    protected double calculate(double leg1, double leg2, double direct) {
        double synthetic = leg1 * leg2;
        return direct - synthetic;
    }

}
