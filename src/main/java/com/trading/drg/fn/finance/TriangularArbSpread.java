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
    protected double calculate(double eurUsd, double usdJpy, double eurJpy) {
        // 1. Calculate Synthetic EUR/JPY
        // (EUR/USD) * (USD/JPY) = EUR/JPY
        double synthetic = eurUsd * usdJpy;

        // 2. Calculate Spread
        // Direct - Synthetic
        return eurJpy - synthetic;
    }
}
