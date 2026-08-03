package com.trading.drg.node;

import com.trading.drg.api.ScalarCutoffs;
import com.trading.drg.api.ScalarValue;

/**
 * Base class for all stateful scalar nodes driven by periodic timer tick events
 * (and/or data updates).
 *
 * <p>Handles graph dependency tracking for the timer and data sources, time measurement
 * via {@link System#nanoTime()}, NaN safety, and initial observation lifecycle.
 */
public abstract class AbstractTimerDrivenNode extends ScalarNode {
    protected final ScalarValue input;
    protected final ScalarValue timer;
    protected long lastTimeNanos = -1;

    /**
     * Dual-input constructor: data source + timer source.
     *
     * @param name  The unique name of the node
     * @param input The upstream data source
     * @param timer The upstream timer source
     */
    public AbstractTimerDrivenNode(String name, ScalarValue input, ScalarValue timer) {
        super(name, ScalarCutoffs.EXACT);
        this.input = input;
        this.timer = timer;
    }

    /**
     * Single-input constructor: timer source is also the data source.
     *
     * @param name       The unique name of the node
     * @param timerInput The upstream timer data source
     */
    public AbstractTimerDrivenNode(String name, ScalarValue timerInput) {
        this(name, timerInput, timerInput);
    }

    @Override
    protected double compute() {
        long now = System.nanoTime();
        double currentInput = input.value();

        // Access timer value to ensure downstream evaluation dependency tracking
        if (timer != null && timer != input) {
            timer.value();
        }

        if (Double.isNaN(currentInput)) {
            return Double.NaN;
        }

        if (lastTimeNanos == -1) {
            lastTimeNanos = now;
            return onInitialObservation(currentInput, now);
        }

        long elapsedNanos = now - lastTimeNanos;
        if (elapsedNanos <= 0) {
            return currentCalculatedValue();
        }

        double result = computeTimerStep(currentInput, elapsedNanos, now);
        lastTimeNanos = now;
        return result;
    }

    /**
     * Called on the first valid non-NaN observation to initialize calculation state.
     *
     * @param initialInput First valid input value
     * @param nowNanos     Current timestamp in nanoseconds
     * @return Initial calculation result
     */
    protected abstract double onInitialObservation(double initialInput, long nowNanos);

    /**
     * Returns the currently stored calculation result when 0 time has elapsed.
     */
    protected abstract double currentCalculatedValue();

    /**
     * Computes the new state when physical time has elapsed.
     *
     * @param currentInput Current input value
     * @param elapsedNanos Nanoseconds elapsed since last step
     * @param nowNanos     Current timestamp in nanoseconds
     * @return Updated calculation result
     */
    protected abstract double computeTimerStep(double currentInput, long elapsedNanos, long nowNanos);
}
