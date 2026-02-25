package com.trading.drg.node;

import com.trading.drg.api.DynamicState;
import com.trading.drg.api.ScalarCutoff;

/**
 * A general-purpose double node that delegates computation to a functional
 * interface.
 *
 * This is the "lambda node" of the graph. It allows users to define node logic
 * inline
 * without creating a new class for every mathematical operation.
 *
 * Example:
 * new ScalarCalcNode("Sum", ScalarCutoffs.EXACT, () -> inputA.doubleValue() +
 * inputB.doubleValue());
 *
 * Performance:
 * The JVM can often inline the lambda execution, making this very efficient.
 */
public final class ScalarCalcNode extends ScalarNode implements DynamicState {
    private final CalcFn fn;
    private DynamicState stateExtractor;

    public ScalarCalcNode(String name, ScalarCutoff cutoff, CalcFn fn) {
        super(name, cutoff);
        this.fn = fn;
    }

    public ScalarCalcNode withStateExtractor(Object obj) {
        if (obj instanceof com.trading.drg.api.DynamicState ds) {
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
