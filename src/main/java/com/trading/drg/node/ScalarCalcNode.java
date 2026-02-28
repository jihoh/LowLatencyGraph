package com.trading.drg.node;

import com.trading.drg.api.DynamicState;
import com.trading.drg.api.ScalarCutoff;

/**
 * A general-purpose scalar node that delegates computation to a functional
 * interface.
 */
public final class ScalarCalcNode extends ScalarNode implements DynamicState {
    private final CalcFn fn;
    private DynamicState stateExtractor;

    public ScalarCalcNode(String name, ScalarCutoff cutoff, CalcFn fn) {
        super(name, cutoff);
        this.fn = fn;
    }

    public ScalarCalcNode withStateExtractor(Object obj) {
        if (obj instanceof DynamicState ds) {
            this.stateExtractor = ds;
        }
        return this;
    }

    @Override
    public void serializeDynamicState(StringBuilder sb) {
        if (stateExtractor != null) {
            stateExtractor.serializeDynamicState(sb);
        }
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
