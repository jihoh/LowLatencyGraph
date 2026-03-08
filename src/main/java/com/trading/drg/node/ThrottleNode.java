package com.trading.drg.node;

import com.trading.drg.api.ScalarValue;
import com.trading.drg.api.ScalarCutoffs;

/**
 * A pass-through node that throttles incoming events, ensuring the value 
 * updates at most once every specified duration (in milliseconds).
 * Intended for rate-limiting heavy downstream calculations or orders.
 */
public class ThrottleNode extends ScalarNode {
    private final ScalarValue input;
    private final long throttleNanos;
    
    // Using Long.MIN_VALUE / 2 to ensure the first update always fires
    private long lastFiredTime = Long.MIN_VALUE / 2;

    public ThrottleNode(String name, ScalarValue input, long throttleMs) {
        super(name, ScalarCutoffs.EXACT); // Will suppress propagation if previousValue == currentValue
        this.input = input;
        this.throttleNanos = throttleMs * 1_000_000L;
    }

    @Override
    protected double compute() {
        long now = System.nanoTime();
        if ((now - lastFiredTime) >= throttleNanos) {
            lastFiredTime = now;
            return input.value();
        }
        
        // Suppress update by returning the exact previous value
        return previousValue();
    }
}
