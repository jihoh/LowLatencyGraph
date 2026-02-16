package com.trading.drg.node;

import com.trading.drg.api.*;

import com.trading.drg.api.Node;
import com.trading.drg.fn.VectorFn;

/**
 * A general-purpose vector node that delegates computation to a functional
 * interface.
 *
 * This allows defining vector logic (like curve interpolation or shifting)
 * inline.
 * The functional interface receives the input nodes and the output array to
 * write to.
 */
public final class VectorCalcNode extends VectorNode {
    private final Node<?>[] inputs;
    private final VectorFn fn;

    public VectorCalcNode(String name, int size, double tolerance,
            Node<?>[] inputs, VectorFn fn) {
        super(name, size, tolerance);
        this.inputs = inputs;
        this.fn = fn;
    }

    @Override
    protected void compute(double[] output) {
        fn.compute(inputs, output);
    }
}
