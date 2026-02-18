package com.trading.drg.node;

import com.trading.drg.api.Node;
import com.trading.drg.api.ScalarValue;
import com.trading.drg.fn.Fn2;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.api.ScalarCutoff;
import com.trading.drg.util.ScalarCutoffs;

/**
 * A generic node that wraps an {@link Fn2} and supports JSON dependency
 * injection.
 * Use this to avoid creating specific *Node classes for every single function.
 */
public class GenericFn2Node extends ScalarNode implements JsonGraphCompiler.DependencyInjectable {
    private final Fn2 fn;
    private ScalarValue in1, in2;

    public GenericFn2Node(String name, Fn2 fn) {
        this(name, fn, ScalarCutoffs.EXACT);
    }

    public GenericFn2Node(String name, Fn2 fn, ScalarCutoff cutoff) {
        super(name, cutoff);
        this.fn = fn;
    }

    @Override
    public void injectDependencies(Node<?>[] upstreams) {
        if (upstreams.length < 2) {
            throw new IllegalArgumentException("GenericFn2Node requires 2 upstream inputs");
        }
        this.in1 = (ScalarValue) upstreams[0];
        this.in2 = (ScalarValue) upstreams[1];
    }

    @Override
    protected double compute() {
        return fn.apply(in1.doubleValue(), in2.doubleValue());
    }
}
