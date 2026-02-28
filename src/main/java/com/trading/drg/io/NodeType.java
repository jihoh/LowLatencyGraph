package com.trading.drg.io;

import com.trading.drg.api.ScalarValue;
import com.trading.drg.fn.finance.*;
import com.trading.drg.node.*;

public enum NodeType {
    EWMA(Ewma.class, (name, props, deps) -> {
        var fn = new Ewma(JsonGraphCompiler.getDouble(props, "alpha", 0.1));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }),
    DIFF(Diff.class, (name, props, deps) -> {
        var fn = new Diff();
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }),
    HIST_VOL(HistVol.class, (name, props, deps) -> {
        var fn = new HistVol(JsonGraphCompiler.getInt(props, "window", 10));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }),
    LOG_RETURN(LogReturn.class, (name, props, deps) -> {
        var fn = new LogReturn();
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }),
    MACD(Macd.class, (name, props, deps) -> {
        var fn = new Macd(JsonGraphCompiler.getInt(props, "fast", 12),
                JsonGraphCompiler.getInt(props, "slow", 26));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }),
    ROLLING_MAX(RollingMax.class, (name, props, deps) -> {
        var fn = new RollingMax(JsonGraphCompiler.getInt(props, "window", 10));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }),
    ROLLING_MIN(RollingMin.class, (name, props, deps) -> {
        var fn = new RollingMin(JsonGraphCompiler.getInt(props, "window", 10));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }),
    RSI(Rsi.class, (name, props, deps) -> {
        var fn = new Rsi(JsonGraphCompiler.getInt(props, "window", 14));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }),
    SMA(Sma.class, (name, props, deps) -> {
        var fn = new Sma(JsonGraphCompiler.getInt(props, "window", 10));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }),
    Z_SCORE(ZScore.class, (name, props, deps) -> {
        var fn = new ZScore(JsonGraphCompiler.getInt(props, "window", 20));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }),

    // --- Fn2 Nodes ---
    SPREAD(Spread.class, (name, props, deps) -> {
        var fn = new Spread();
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()))
                .withStateExtractor(fn);
    }),
    BETA(Beta.class, (name, props, deps) -> {
        var fn = new Beta(JsonGraphCompiler.getInt(props, "window", 20));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()))
                .withStateExtractor(fn);
    }),
    CORRELATION(Correlation.class, (name, props, deps) -> {
        var fn = new Correlation(JsonGraphCompiler.getInt(props, "window", 20));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()))
                .withStateExtractor(fn);
    }),
    TRI_ARB_SPREAD(TriangularArbSpread.class, (name, props, deps) -> {
        var fn = new TriangularArbSpread();
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue(),
                        ((ScalarValue) deps[1]).doubleValue(), ((ScalarValue) deps[2]).doubleValue()))
                .withStateExtractor(fn);
    }),
    HARMONIC_MEAN(HarmonicMean.class, (name, props, deps) -> {
        var fn = new HarmonicMean();
        double[] scratch = new double[deps.length];
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
            for (int i = 0; i < deps.length; i++)
                scratch[i] = ((ScalarValue) deps[i]).doubleValue();
            return fn.apply(scratch);
        }).withStateExtractor(fn);
    }),
    WEIGHTED_AVG(WeightedAverage.class, (name, props, deps) -> {
        var fn = new WeightedAverage();
        double[] scratch = new double[deps.length];
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
            for (int i = 0; i < deps.length; i++)
                scratch[i] = ((ScalarValue) deps[i]).doubleValue();
            return fn.apply(scratch);
        }).withStateExtractor(fn);
    }),
    AVERAGE(Average.class, (name, props, deps) -> {
        var fn = new Average();
        double[] scratch = new double[deps.length];
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
            for (int i = 0; i < deps.length; i++)
                scratch[i] = ((ScalarValue) deps[i]).doubleValue();
            return fn.apply(scratch);
        }).withStateExtractor(fn);
    }),
    SCALAR_SOURCE(ScalarSourceNode.class, (name, props, deps) -> {
        return new ScalarSourceNode(name, JsonGraphCompiler.getDouble(props, "value", 0.0),
                JsonGraphCompiler.parseCutoff(props));
    }),
    VECTOR_SOURCE(VectorSourceNode.class, (name, props, deps) -> {
        int size = JsonGraphCompiler.getInt(props, "size", 0);
        if (size <= 0)
            throw new IllegalArgumentException("vector_source needs positive 'size'");

        double tolerance = JsonGraphCompiler.getDouble(props, "tolerance", 1e-15);
        var node = new VectorSourceNode(name, size, tolerance);

        Object headersObj = props.get("headers");
        if (headersObj == null) {
            headersObj = props.get("auto_expand_labels");
        }

        if (headersObj instanceof java.util.List<?> list) {
            String[] headers = list.stream().map(Object::toString).toArray(String[]::new);
            node.withHeaders(headers);
        }
        return node;
    }),
    VECTOR_ELEMENT(ScalarCalcNode.class, (name, props, deps) -> {
        int index = JsonGraphCompiler.getInt(props, "index", 0);
        return com.trading.drg.dsl.GraphBuilder.create("tmp").vectorElement(name,
                (com.trading.drg.api.VectorValue) deps[0], index);
    }),
    COMPUTE_VECTOR(VectorCalcNode.class, (name, props, deps) -> {
        int size = JsonGraphCompiler.getInt(props, "size", -1);
        double tolerance = JsonGraphCompiler.getDouble(props, "tolerance", 1e-15);
        // We provide a simple proxy VectorFn that just copies the first dependent
        // vector
        // In a real scenario, we'd use reflection or a registry to resolve 'fn'
        return com.trading.drg.dsl.GraphBuilder.create("tmp").computeVector(name, size, tolerance, (inNodes, out) -> {
            if (inNodes.length > 0 && inNodes[0] instanceof com.trading.drg.api.VectorValue v) {
                for (int i = 0; i < Math.min(size, v.size()); i++)
                    out[i] = v.valueAt(i);
            }
        }, deps);
    }),
    TEMPLATE(null, null);

    private final Class<?> nodeClass;
    private final JsonGraphCompiler.NodeFactory factory;

    NodeType(Class<?> nodeClass, JsonGraphCompiler.NodeFactory factory) {
        this.nodeClass = nodeClass;
        this.factory = factory;
    }

    public Class<?> getNodeClass() {
        return nodeClass;
    }

    public JsonGraphCompiler.NodeFactory getFactory() {
        return factory;
    }

    public static NodeType fromString(String text) {
        for (NodeType b : NodeType.values()) {
            if (b.name().equalsIgnoreCase(text)) {
                return b;
            }
        }
        throw new IllegalArgumentException("Unknown NodeType: " + text);
    }
}
