package com.trading.drg.node;

import com.trading.drg.api.ScalarCutoff;

/**
 * A general-purpose scalar node that delegates computation to a functional
 * interface.
 */
public final class ScalarCalcNode extends ScalarNode {
    private final CalcFn fn;

    public ScalarCalcNode(String name, ScalarCutoff cutoff, CalcFn fn) {
        super(name, cutoff);
        this.fn = fn;
    }

    @Override
    protected double compute() {
        return fn.compute();
    }

    /** The computation logic. */
    @FunctionalInterface
    public interface CalcFn {
        double compute();
    }
}
