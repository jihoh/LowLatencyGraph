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
    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager
            .getLogger(TriangularArbSpread.class);
    private final com.trading.drg.util.ErrorRateLimiter limiter = new com.trading.drg.util.ErrorRateLimiter(log, 1000);

    @Override
    public double apply(double eurUsd, double usdJpy, double eurJpy) {
        try {
            if (Double.isNaN(eurUsd) || Double.isNaN(usdJpy) || Double.isNaN(eurJpy)) {
                return Double.NaN;
            }
            // 1. Calculate Synthetic EUR/JPY
            // (EUR/USD) * (USD/JPY) = EUR/JPY
            double synthetic = eurUsd * usdJpy;

            // 2. Calculate Spread
            // Direct - Synthetic
            return eurJpy - synthetic;
        } catch (Throwable t) {
            limiter.log("Error in TriangularArbSpread", t);
            return Double.NaN;
        }
    }
}
