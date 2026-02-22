package com.trading.drg.io;

import com.trading.drg.api.ScalarValue;
import com.trading.drg.node.ScalarCalcNode;
import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.node.VectorSourceNode;
import com.trading.drg.api.ScalarCutoff;

import java.util.HashMap;
import java.util.Map;

public final class NodeRegistry {

    private final Map<NodeType, JsonGraphCompiler.NodeFactory> factories = new HashMap<>();

    public NodeRegistry() {
        registerBuiltIns();
    }

    public void registerFactory(NodeType type, JsonGraphCompiler.NodeFactory factory) {
        factories.put(type, factory);
    }

    public JsonGraphCompiler.NodeFactory getFactory(NodeType type) {
        return factories.get(type);
    }

    public void registerBuiltIns() {
        // --- Fn1 Nodes ---
        registerFactory(NodeType.EWMA, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Ewma(JsonGraphCompiler.getDouble(props, "alpha", 0.1));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory(NodeType.DIFF, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Diff();
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory(NodeType.HIST_VOL, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.HistVol(JsonGraphCompiler.getInt(props, "window", 10));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory(NodeType.LOG_RETURN, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.LogReturn();
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory(NodeType.MACD, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Macd(JsonGraphCompiler.getInt(props, "fast", 12),
                    JsonGraphCompiler.getInt(props, "slow", 26));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory(NodeType.ROLLING_MAX, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.RollingMax(JsonGraphCompiler.getInt(props, "window", 10));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory(NodeType.ROLLING_MIN, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.RollingMin(JsonGraphCompiler.getInt(props, "window", 10));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory(NodeType.RSI, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Rsi(JsonGraphCompiler.getInt(props, "window", 14));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory(NodeType.SMA, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Sma(JsonGraphCompiler.getInt(props, "window", 10));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory(NodeType.Z_SCORE, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.ZScore(JsonGraphCompiler.getInt(props, "window", 20));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        // --- Fn2 Nodes ---
        registerFactory(NodeType.BETA, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Beta(JsonGraphCompiler.getInt(props, "window", 20));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()));
        });

        registerFactory(NodeType.CORRELATION, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Correlation(JsonGraphCompiler.getInt(props, "window", 20));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()));
        });

        // --- Fn3 Nodes ---
        registerFactory(NodeType.TRI_ARB_SPREAD, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.TriangularArbSpread();
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue(),
                            ((ScalarValue) deps[1]).doubleValue(), ((ScalarValue) deps[2]).doubleValue()));
        });

        // --- FnN Nodes ---
        registerFactory(NodeType.HARMONIC_MEAN, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.HarmonicMean();
            double[] scratch = new double[deps.length];
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
                for (int i = 0; i < deps.length; i++)
                    scratch[i] = ((ScalarValue) deps[i]).doubleValue();
                return fn.apply(scratch);
            });
        });

        registerFactory(NodeType.WEIGHTED_AVG, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.WeightedAverage();
            double[] scratch = new double[deps.length];
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
                for (int i = 0; i < deps.length; i++)
                    scratch[i] = ((ScalarValue) deps[i]).doubleValue();
                return fn.apply(scratch);
            });
        });

        registerFactory(NodeType.AVERAGE, (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Average();
            double[] scratch = new double[deps.length];
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
                for (int i = 0; i < deps.length; i++)
                    scratch[i] = ((ScalarValue) deps[i]).doubleValue();
                return fn.apply(scratch);
            });
        });

        registerFactory(NodeType.SCALAR_SOURCE, (name, props, deps) -> {
            double init = JsonGraphCompiler.getDouble(props, "initial_value", Double.NaN);
            ScalarCutoff cutoff = JsonGraphCompiler.parseCutoff(props);
            return new ScalarSourceNode(name, init, cutoff);
        });
        registerFactory(NodeType.DOUBLE_SOURCE, (name, props, deps) -> {
            double init = JsonGraphCompiler.getDouble(props, "initial_value", Double.NaN);
            ScalarCutoff cutoff = JsonGraphCompiler.parseCutoff(props);
            return new ScalarSourceNode(name, init, cutoff);
        });
        registerFactory(NodeType.VECTOR_SOURCE, (name, props, deps) -> {
            int size = JsonGraphCompiler.getInt(props, "size", -1);
            if (size <= 0)
                throw new IllegalArgumentException("vector_source needs positive 'size'");
            return new VectorSourceNode(name, size, JsonGraphCompiler.getDouble(props, "tolerance", 1e-15));
        });
    }

}
