package com.trading.drg.node;

import com.trading.drg.api.ScalarValue;

/**
 * A stateful node that calculates a time-elapsed Exponentially Weighted Moving Average (EWMA)
 * driven by periodic timer tick events (or data updates).
 */
public class TimerEwmaNode extends AbstractTimerDrivenNode {
    private final double halfLifeNanos;
    private double ewma = Double.NaN;

    /**
     * Dual-input constructor: data source + timer source.
     *
     * @param name       The unique name of the node
     * @param input      The upstream data source to average (e.g. price stream)
     * @param timer      The upstream timer trigger source (e.g. TimerSourceNode)
     * @param halfLifeMs The half-life of the exponential decay in milliseconds
     */
    public TimerEwmaNode(String name, ScalarValue input, ScalarValue timer, long halfLifeMs) {
        super(name, input, timer);
        this.halfLifeNanos = halfLifeMs * 1_000_000.0;
    }

    /**
     * Single-input constructor: timer source is also the data source.
     *
     * @param name       The unique name of the node
     * @param timerInput The upstream timer data source
     * @param halfLifeMs The half-life of the exponential decay in milliseconds
     */
    public TimerEwmaNode(String name, ScalarValue timerInput, long halfLifeMs) {
        this(name, timerInput, timerInput, halfLifeMs);
    }

    @Override
    protected double onInitialObservation(double initialInput, long nowNanos) {
        this.ewma = initialInput;
        return ewma;
    }

    @Override
    protected double currentCalculatedValue() {
        return ewma;
    }

    @Override
    protected double computeTimerStep(double currentInput, long elapsedNanos, long nowNanos) {
        double decay = Math.exp((-0.6931471805599453 * elapsedNanos) / halfLifeNanos);
        ewma = (currentInput * (1.0 - decay)) + (ewma * decay);
        return ewma;
    }
}
