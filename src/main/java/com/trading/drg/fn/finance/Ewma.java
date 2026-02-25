package com.trading.drg.fn.finance;

/**
 * Exponential Moving Average (EWMA).
 * 
 * Formula:
 * y[t] = alpha * x[t] + (1 - alpha) * y[t-1]
 */
public class Ewma extends AbstractFn1 implements com.trading.drg.api.DynamicState {
    private final double alpha;
    private double state;
    private boolean initialized = false;

    public Ewma(double alpha) {
        if (alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("Alpha must be in (0, 1]");
        }
        this.alpha = alpha;
    }

    @Override
    protected double calculate(double input) {
        // Handle First Tick
        if (!initialized) {
            state = input;
            initialized = true;
            return state;
        }

        // Classic EWMA Formula:
        // New = Alpha * Input + (1 - Alpha) * Old
        state = alpha * input + (1.0 - alpha) * state;
        return state;
    }

    @Override
    public void serializeDynamicState(StringBuilder sb) {
        sb.append("\"alpha\":").append(this.alpha);
        sb.append(",\"state\":").append(this.state);
        sb.append(",\"initialized\":").append(this.initialized);
    }
}
