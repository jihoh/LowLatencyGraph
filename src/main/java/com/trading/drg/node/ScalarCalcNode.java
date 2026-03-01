package com.trading.drg.node;

import com.trading.drg.api.ScalarCutoff;
import com.trading.drg.util.ErrorRateLimiter;

/**
 * A general-purpose scalar node that delegates computation to a functional
 * interface.
 */
public final class ScalarCalcNode extends ScalarNode {
    private final CalcFn fn;
    private final ErrorRateLimiter limiter = new ErrorRateLimiter();

    public ScalarCalcNode(String name, ScalarCutoff cutoff, CalcFn fn) {
        super(name, cutoff);
        this.fn = fn;
    }

    @Override
    protected double compute() {
        if (limiter.isCircuitOpen()) {
            return Double.NaN;
        }
        try {
            return fn.compute();
        } catch (Throwable t) {
            limiter.log("Error evaluating " + this.name(), t);
            return Double.NaN;
        }
    }

    /** The computation logic. */
    @FunctionalInterface
    public interface CalcFn {
        double compute();
    }
}
