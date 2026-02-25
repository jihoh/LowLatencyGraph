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
    }, "Calculates the Exponentially Weighted Moving Average (EWMA) with configuring smoothing factor alpha."),
    DIFF(Diff.class, (name, props, deps) -> {
        var fn = new Diff();
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates the difference between the input and a reference value."),
    HIST_VOL(HistVol.class, (name, props, deps) -> {
        var fn = new HistVol(JsonGraphCompiler.getInt(props, "window", 10));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates Historical Volatility (Standard Deviation) over a rolling window."),
    LOG_RETURN(LogReturn.class, (name, props, deps) -> {
        var fn = new LogReturn();
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates the natural logarithmic return from the previous tick to the current tick."),
    MACD(Macd.class, (name, props, deps) -> {
        var fn = new Macd(JsonGraphCompiler.getInt(props, "fast", 12),
                JsonGraphCompiler.getInt(props, "slow", 26));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates the Moving Average Convergence Divergence (MACD) using fast and slow periods."),
    ROLLING_MAX(RollingMax.class, (name, props, deps) -> {
        var fn = new RollingMax(JsonGraphCompiler.getInt(props, "window", 10));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates the maximum value over a rolling window."),
    ROLLING_MIN(RollingMin.class, (name, props, deps) -> {
        var fn = new RollingMin(JsonGraphCompiler.getInt(props, "window", 10));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates the minimum value over a rolling window."),
    RSI(Rsi.class, (name, props, deps) -> {
        var fn = new Rsi(JsonGraphCompiler.getInt(props, "window", 14));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates the Relative Strength Index (RSI) over a rolling window."),
    SMA(Sma.class, (name, props, deps) -> {
        var fn = new Sma(JsonGraphCompiler.getInt(props, "window", 10));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates the Simple Moving Average (SMA) over a rolling window."),
    Z_SCORE(ZScore.class, (name, props, deps) -> {
        var fn = new ZScore(JsonGraphCompiler.getInt(props, "window", 20));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates the Z-Score (standard deviations from the mean) over a rolling window."),

    // --- Fn2 Nodes ---
    SPREAD(Spread.class, (name, props, deps) -> {
        var fn = new Spread();
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates the simple spread difference: minuend - subtrahend."),
    BETA(Beta.class, (name, props, deps) -> {
        var fn = new Beta(JsonGraphCompiler.getInt(props, "window", 20));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates the Beta (Covariance(Asset, Market) / Variance(Market)) over a rolling window."),
    CORRELATION(Correlation.class, (name, props, deps) -> {
        var fn = new Correlation(JsonGraphCompiler.getInt(props, "window", 20));
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates the Pearson Correlation Coefficient between two arrays over a rolling window."),
    TRI_ARB_SPREAD(TriangularArbSpread.class, (name, props, deps) -> {
        var fn = new TriangularArbSpread();
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                () -> fn.apply(((ScalarValue) deps[0]).doubleValue(),
                        ((ScalarValue) deps[1]).doubleValue(), ((ScalarValue) deps[2]).doubleValue()))
                .withStateExtractor(fn);
    }, "Calculates Triangular Arbitrage Spread: (leg1 * leg2) - direct."),
    HARMONIC_MEAN(HarmonicMean.class, (name, props, deps) -> {
        var fn = new HarmonicMean();
        double[] scratch = new double[deps.length];
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
            for (int i = 0; i < deps.length; i++)
                scratch[i] = ((ScalarValue) deps[i]).doubleValue();
            return fn.apply(scratch);
        }).withStateExtractor(fn);
    }, "Calculates the Harmonic Mean across all static inputs."),
    WEIGHTED_AVG(WeightedAverage.class, (name, props, deps) -> {
        var fn = new WeightedAverage();
        double[] scratch = new double[deps.length];
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
            for (int i = 0; i < deps.length; i++)
                scratch[i] = ((ScalarValue) deps[i]).doubleValue();
            return fn.apply(scratch);
        }).withStateExtractor(fn);
    }, "Calculates the weighted average across all static inputs using configured weights."),
    AVERAGE(Average.class, (name, props, deps) -> {
        var fn = new Average();
        double[] scratch = new double[deps.length];
        return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
            for (int i = 0; i < deps.length; i++)
                scratch[i] = ((ScalarValue) deps[i]).doubleValue();
            return fn.apply(scratch);
        }).withStateExtractor(fn);
    }, "Calculates the arithmetic mean across all static inputs."),
    SCALAR_SOURCE(ScalarSourceNode.class, (name, props, deps) -> {
        return new ScalarSourceNode(name, JsonGraphCompiler.getDouble(props, "value", 0.0),
                JsonGraphCompiler.parseCutoff(props));
    }, "Produces a constant scalar value."),
    VECTOR_SOURCE(VectorSourceNode.class, (name, props, deps) -> {
        int size = JsonGraphCompiler.getInt(props, "size", -1);
        if (size <= 0)
            throw new IllegalArgumentException("vector_source needs positive 'size'");
        return new VectorSourceNode(name, size, JsonGraphCompiler.getDouble(props, "tolerance", 1e-15));
    }, "Produces a constant vector of values."),
    TEMPLATE(null, null, "Defines a template for node grouping.");

    private final Class<?> nodeClass;
    private final JsonGraphCompiler.NodeFactory factory;
    private final String description;

    NodeType(Class<?> nodeClass, JsonGraphCompiler.NodeFactory factory) {
        this.nodeClass = nodeClass;
        this.factory = factory;
        this.description = "";
    }

    NodeType(Class<?> nodeClass, JsonGraphCompiler.NodeFactory factory, String description) {
        this.nodeClass = nodeClass;
        this.factory = factory;
        this.description = description;
    }

    public Class<?> getNodeClass() {
        return nodeClass;
    }

    public JsonGraphCompiler.NodeFactory getFactory() {
        return factory;
    }

    public String getDescription() {
        return description;
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
