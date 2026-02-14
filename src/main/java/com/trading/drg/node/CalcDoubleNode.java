package com.trading.drg.node;

import com.trading.drg.core.DoubleCutoff;

/**
 * A general-purpose double node that delegates computation to a functional
 * interface.
 *
 * <p>
 * This is the "lambda node" of the graph. It allows users to define node logic
 * inline
 * without creating a new class for every mathematical operation.
 *
 * <p>
 * <b>Example:</b>
 * 
 * <pre>{@code
 * new CalcDoubleNode("Sum", DoubleCutoffs.EXACT, () -> inputA.doubleValue() + inputB.doubleValue());
 * }</pre>
 */
public final class CalcDoubleNode extends DoubleNode {
    private final CalcFn fn;

    public CalcDoubleNode(String name, DoubleCutoff cutoff, CalcFn fn) {
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
