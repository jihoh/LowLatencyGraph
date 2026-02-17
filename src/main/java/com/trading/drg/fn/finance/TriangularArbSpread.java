package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn3;

/**
 * Calculates the arbitrage spread between a Direct rate and a Synthetic rate.
 *
 * Spread = Direct - (Leg1 * Leg2)
 *
 * Example: Spread = EUR/JPY - (EUR/USD * USD/JPY)
 */
public class TriangularArbSpread implements Fn3 {
    @Override
    public double apply(double leg1, double leg2, double direct) {
        // leg1: EUR/USD
        // leg2: USD/JPY
        // direct: EUR/JPY

        double synthetic = leg1 * leg2;
        return direct - synthetic;
    }
}
