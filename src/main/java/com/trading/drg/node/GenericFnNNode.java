package com.trading.drg.node;

import com.trading.drg.api.Node;
import com.trading.drg.api.ScalarValue;
import com.trading.drg.fn.FnN;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.api.ScalarCutoff;
import com.trading.drg.util.ScalarCutoffs;

/**
 * A generic node that wraps an {@link FnN} and supports JSON dependency
 * injection.
 * Use this to avoid creating specific *Node classes for every single function.
 */
public class GenericFnNNode extends ScalarNode implements JsonGraphCompiler.DependencyInjectable {
    private final FnN fn;
    private ScalarValue[] inputs;
    // Reusable double array to avoid allocation during compute
    private double[] inputValues;

    public GenericFnNNode(String name, FnN fn) {
        this(name, fn, ScalarCutoffs.EXACT);
    }

    public GenericFnNNode(String name, FnN fn, ScalarCutoff cutoff) {
        super(name, cutoff);
        this.fn = fn;
    }

    @Override
    public void injectDependencies(Node<?>[] upstreams) {
        if (upstreams.length == 0) {
            // N-ary functions might validly take 0 inputs, but usually at least 1
            // We'll allow 0 if FnN handles it, but FnN usually expects double[]
        }
        this.inputs = new ScalarValue[upstreams.length];
        this.inputValues = new double[upstreams.length];

        for (int i = 0; i < upstreams.length; i++) {
            this.inputs[i] = (ScalarValue) upstreams[i];
        }
    }

    @Override
    protected double compute() {
        // Copy values to primitive array
        for (int i = 0; i < inputs.length; i++) {
            inputValues[i] = inputs[i].doubleValue();
        }
        return fn.apply(inputValues);
    }
}
