package com.trading.drg.io;

import com.trading.drg.api.ScalarValue;
import com.trading.drg.fn.finance.*;
import com.trading.drg.node.*;

public enum NodeType {
    EWMA(Ewma.class, (name, props, deps) -> {
        var fn = new Ewma(JsonGraphCompiler.getDouble(props, "alpha", 0.1));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
    }),
    DIFF(Diff.class, (name, props, deps) -> {
        var fn = new Diff();
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
    }),
    HIST_VOL(HistVol.class, (name, props, deps) -> {
        var fn = new HistVol(JsonGraphCompiler.getInt(props, "window", 10));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
    }),
    LOG_RETURN(LogReturn.class, (name, props, deps) -> {
        var fn = new LogReturn();
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
    }),
    MACD(Macd.class, (name, props, deps) -> {
        var fn = new Macd(JsonGraphCompiler.getInt(props, "fast", 12),
                JsonGraphCompiler.getInt(props, "slow", 26));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
    }),
    ROLLING_MAX(RollingMax.class, (name, props, deps) -> {
        var fn = new RollingMax(JsonGraphCompiler.getInt(props, "window", 10));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
    }),
    ROLLING_MIN(RollingMin.class, (name, props, deps) -> {
        var fn = new RollingMin(JsonGraphCompiler.getInt(props, "window", 10));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
    }),
    RSI(Rsi.class, (name, props, deps) -> {
        var fn = new Rsi(JsonGraphCompiler.getInt(props, "window", 14));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
    }),
    SMA(Sma.class, (name, props, deps) -> {
        var fn = new Sma(JsonGraphCompiler.getInt(props, "window", 10));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
    }),
    Z_SCORE(ZScore.class, (name, props, deps) -> {
        var fn = new ZScore(JsonGraphCompiler.getInt(props, "window", 20));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
    }),

    // --- Fn2 Nodes ---
    SPREAD(Spread.class, (name, props, deps) -> {
        var fn = new Spread();
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()));
    }),
    BETA(Beta.class, (name, props, deps) -> {
        var fn = new Beta(JsonGraphCompiler.getInt(props, "window", 20));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()));
    }),
    CORRELATION(Correlation.class, (name, props, deps) -> {
        var fn = new Correlation(JsonGraphCompiler.getInt(props, "window", 20));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()));
    }),
    TRI_ARB_SPREAD(TriangularArbSpread.class, (name, props, deps) -> {
        var fn = new TriangularArbSpread();
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue(),
                        ((ScalarValue) deps[1]).doubleValue(), ((ScalarValue) deps[2]).doubleValue()));
    }),
    HARMONIC_MEAN(HarmonicMean.class, (name, props, deps) -> {
        var fn = new HarmonicMean();
        double[] scratch = new double[deps.length];
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
            for (int i = 0; i < deps.length; i++)
                scratch[i] = ((ScalarValue) deps[i]).doubleValue();
            return fn.apply(scratch);
        });
    }),
    WEIGHTED_AVG(WeightedAverage.class, (name, props, deps) -> {
        var fn = new WeightedAverage();
        double[] scratch = new double[deps.length];
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
            for (int i = 0; i < deps.length; i++)
                scratch[i] = ((ScalarValue) deps[i]).doubleValue();
            return fn.apply(scratch);
        });
    }),
    AVERAGE(Average.class, (name, props, deps) -> {
        var fn = new Average();
        double[] scratch = new double[deps.length];
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
            for (int i = 0; i < deps.length; i++)
                scratch[i] = ((ScalarValue) deps[i]).doubleValue();
            return fn.apply(scratch);
        });
    }),
    SCALAR_SOURCE(ScalarSourceNode.class, (name, props, deps) -> {
        return new ScalarSourceNode(name, JsonGraphCompiler.getDouble(props, "value", 0.0),
                JsonGraphCompiler.parseCutoff(props));
    }),
    VECTOR_SOURCE(VectorSourceNode.class, (name, props, deps) -> {
        int size = JsonGraphCompiler.getInt(props, "size", -1);
        if (size <= 0)
            throw new IllegalArgumentException("vector_source needs positive 'size'");
        return new VectorSourceNode(name, size, JsonGraphCompiler.getDouble(props, "tolerance", 1e-15));
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
