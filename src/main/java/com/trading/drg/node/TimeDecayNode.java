package com.trading.drg.node;

import com.trading.drg.api.ScalarValue;
import com.trading.drg.util.ScalarCutoffs;

/**
 * A stateful node that calculates a true time-elapsed Exponentially Weighted Moving Average (EWMA).
 * 
 * Unlike standard tick-based EWMAs which decay per update, this node uses System.nanoTime()
 * to decay the value based on the actual physical time elapsed since the last observation.
 * This is critical in High-Frequency Trading (HFT) where tick density is variable.
 */
public class TimeDecayNode extends ScalarNode {
    private final ScalarValue input;
    private final double halfLifeNanos;
    
    private double ewma = Double.NaN;
    private long lastTime = -1;

    /**
     * @param name The unique name of the node
     * @param input The upstream scalar data source
     * @param halfLifeMs The half-life of the exponential decay in milliseconds.
     */
    public TimeDecayNode(String name, ScalarValue input, long halfLifeMs) {
        super(name, ScalarCutoffs.EXACT);
        this.input = input;
        this.halfLifeNanos = halfLifeMs * 1_000_000.0;
    }

    @Override
    protected double compute() {
        long now = System.nanoTime();
        double currentInput = input.value();

        if (Double.isNaN(currentInput)) {
            // Passthrough invalid states without corrupting the historical EWMA state
            return Double.NaN;
        }

        if (Double.isNaN(ewma) || lastTime == -1) {
            // First valid observation initializes the state
            ewma = currentInput;
            lastTime = now;
            return ewma;
        }

        long elapsedNanos = now - lastTime;
        
        // Prevent negative time on systems with unstable nanotime or extremely rapid successive calls
        if (elapsedNanos <= 0) {
            return ewma;
        }

        // Calculate the continuous time decay factor: alpha = 1 - exp(-ln(2) * elapsed / halflife)
        // Optimization: decay = exp(-ln(2) * elapsed / halflife)
        double decay = Math.exp((-0.6931471805599453 * elapsedNanos) / halfLifeNanos);
        
        // EWMA formula: (value * (1 - decay)) + (previousEwma * decay)
        ewma = (currentInput * (1.0 - decay)) + (ewma * decay);
        lastTime = now;

        return ewma;
    }
}
