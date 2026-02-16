package com.trading.drg.node;

import com.trading.drg.api.*;

import com.trading.drg.api.DoubleCutoff;

/**
 * A general-purpose double node that delegates computation to a functional
 * interface.
 *
 * This is the "lambda node" of the graph. It allows users to define node logic
 * inline
 * without creating a new class for every mathematical operation.
 *
 * Example:
 * new CalcDoubleNode("Sum", DoubleCutoffs.EXACT, () -> inputA.doubleValue() +
 * inputB.doubleValue());
 *
 * Performance:
 * The JVM can often inline the lambda execution, making this very efficient.
 */
public final class DoubleCalcNode extends DoubleNode {
    private final CalcFn fn;

    public DoubleCalcNode(String name, DoubleCutoff cutoff, CalcFn fn) {
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
