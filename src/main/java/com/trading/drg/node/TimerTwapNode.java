package com.trading.drg.node;

import com.trading.drg.api.ScalarValue;

/**
 * A stateful node that calculates Time-Weighted Average Price (TWAP)
 * driven by periodic timer tick events (or data updates).
 *
 * <p>Formally calculates:
 * <pre>
 * TWAP = ∫ P(t) dt / ∫ dt
 * </pre>
 * accumulating price × elapsed time over timer steps.
 */
public class TimerTwapNode extends AbstractTimerDrivenNode {
    private double weightedSum = 0.0;
    private double totalTimeSec = 0.0;
    private double twap = Double.NaN;
    private double lastInput = Double.NaN;

    /**
     * Dual-input constructor: data source + timer source.
     *
     * @param name  The unique name of the node
     * @param input The upstream data source (e.g. price stream)
     * @param timer The upstream timer source (e.g. TimerSourceNode)
     */
    public TimerTwapNode(String name, ScalarValue input, ScalarValue timer) {
        super(name, input, timer);
    }

    /**
     * Single-input constructor: timer source is also the data source.
     *
     * @param name       The unique name of the node
     * @param timerInput The upstream timer data source
     */
    public TimerTwapNode(String name, ScalarValue timerInput) {
        this(name, timerInput, timerInput);
    }

    @Override
    protected double onInitialObservation(double initialInput, long nowNanos) {
        this.lastInput = initialInput;
        this.twap = initialInput;
        return twap;
    }

    @Override
    protected double currentCalculatedValue() {
        return twap;
    }

    @Override
    protected double computeTimerStep(double currentInput, long elapsedNanos, long nowNanos) {
        double deltaSec = elapsedNanos / 1_000_000_000.0;

        // Piecewise constant trapezoidal / rectangular integration:
        // Weight the previous input over the physical elapsed duration deltaSec
        weightedSum += lastInput * deltaSec;
        totalTimeSec += deltaSec;

        if (totalTimeSec > 0) {
            twap = weightedSum / totalTimeSec;
        } else {
            twap = currentInput;
        }

        lastInput = currentInput;
        return twap;
    }
}
