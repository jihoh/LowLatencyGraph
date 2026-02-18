package com.trading.drg.node;

import com.trading.drg.api.Node;
import com.trading.drg.api.ScalarValue;
import com.trading.drg.fn.Fn1;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.api.ScalarCutoff;
import com.trading.drg.util.ScalarCutoffs;

/**
 * A generic node that wraps an {@link Fn1} and supports JSON dependency
 * injection.
 * Use this to avoid creating specific *Node classes for every single function.
 */
public class GenericFn1Node extends ScalarNode implements JsonGraphCompiler.DependencyInjectable {
    private final Fn1 fn;
    private ScalarValue input;

    public GenericFn1Node(String name, Fn1 fn) {
        this(name, fn, ScalarCutoffs.EXACT);
    }

    public GenericFn1Node(String name, Fn1 fn, ScalarCutoff cutoff) {
        super(name, cutoff);
        this.fn = fn;
    }

    @Override
    public void injectDependencies(Node<?>[] upstreams) {
        if (upstreams.length < 1) {
            throw new IllegalArgumentException("GenericFn1Node requires 1 upstream input");
        }
        this.input = (ScalarValue) upstreams[0];
    }

    @Override
    protected double compute() {
        return fn.apply(input.doubleValue());
    }
}
