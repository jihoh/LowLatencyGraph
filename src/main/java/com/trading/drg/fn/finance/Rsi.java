package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn1;

/**
 * Relative Strength Index (RSI).
 * 
 * Uses Wilder's Smoothing for Avg Gain/Loss.
 * RSI = 100 - (100 / (1 + RS))
 * RS = AvgGain / AvgLoss
 */
public class Rsi implements Fn1 {
    private final double alpha; // 1/N for Wilder's
    private double avgGain;
    private double avgLoss;
    private double prevPrice;
    private boolean initialized = false;

    public Rsi(int period) {
        if (period < 1)
            throw new IllegalArgumentException("Period must be >= 1");
        this.alpha = 1.0 / period;
    }

    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager.getLogger(Rsi.class);
    private final com.trading.drg.util.ErrorRateLimiter limiter = new com.trading.drg.util.ErrorRateLimiter(log, 1000);

    @Override
    public double apply(double input) {
        try {
            if (Double.isNaN(input)) {
                return Double.NaN;
            }

            if (!initialized) {
                prevPrice = input;
                initialized = true;
                return 50.0; // Start at neutral
            }

            double change = input - prevPrice;
            prevPrice = input;

            double gain = Math.max(0, change);
            double loss = Math.max(0, -change);

            // Wilder's Smoothing: newAvg = alpha * newVal + (1 - alpha) * oldAvg
            avgGain = alpha * gain + (1.0 - alpha) * avgGain;
            avgLoss = alpha * loss + (1.0 - alpha) * avgLoss;

            if (avgLoss == 0) {
                return (avgGain == 0) ? 50.0 : 100.0;
            }

            double rs = avgGain / avgLoss;
            return 100.0 - (100.0 / (1.0 + rs));
        } catch (Throwable t) {
            limiter.log("Error in Rsi", t);
            return Double.NaN;
        }
    }
}
