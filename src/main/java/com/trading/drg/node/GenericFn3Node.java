package com.trading.drg.node;

import com.trading.drg.api.Node;
import com.trading.drg.api.ScalarValue;
import com.trading.drg.fn.Fn3;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.api.ScalarCutoff;
import com.trading.drg.util.ScalarCutoffs;

/**
 * A generic node that wraps an {@link Fn3} and supports JSON dependency
 * injection.
 * Use this to avoid creating specific *Node classes for every single function.
 */
public class GenericFn3Node extends ScalarNode implements JsonGraphCompiler.DependencyInjectable {
    private final Fn3 fn;
    private ScalarValue in1, in2, in3;

    public GenericFn3Node(String name, Fn3 fn) {
        this(name, fn, ScalarCutoffs.EXACT);
    }

    public GenericFn3Node(String name, Fn3 fn, ScalarCutoff cutoff) {
        super(name, cutoff);
        this.fn = fn;
    }

    @Override
    public void injectDependencies(Node<?>[] upstreams) {
        if (upstreams.length < 3) {
            throw new IllegalArgumentException("GenericFn3Node requires 3 upstream inputs");
        }
        this.in1 = (ScalarValue) upstreams[0];
        this.in2 = (ScalarValue) upstreams[1];
        this.in3 = (ScalarValue) upstreams[2];
    }

    @Override
    protected double compute() {
        return fn.apply(in1.doubleValue(), in2.doubleValue(), in3.doubleValue());
    }
}
