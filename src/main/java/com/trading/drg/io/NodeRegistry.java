package com.trading.drg.io;

import com.trading.drg.fn.finance.Average;
import com.trading.drg.fn.finance.Beta;
import com.trading.drg.fn.finance.Correlation;
import com.trading.drg.fn.finance.Diff;
import com.trading.drg.fn.finance.Ewma;
import com.trading.drg.fn.finance.HarmonicMean;
import com.trading.drg.fn.finance.HistVol;
import com.trading.drg.fn.finance.LogReturn;
import com.trading.drg.fn.finance.Macd;
import com.trading.drg.fn.finance.RollingMax;
import com.trading.drg.fn.finance.RollingMin;
import com.trading.drg.fn.finance.Rsi;
import com.trading.drg.fn.finance.Sma;
import com.trading.drg.fn.finance.Spread;
import com.trading.drg.fn.finance.TriangularArbSpread;
import com.trading.drg.fn.finance.WeightedAverage;
import com.trading.drg.fn.finance.ZScore;
import com.trading.drg.node.ScalarCalcNode;
import com.trading.drg.node.ScalarSourceNode;

import com.trading.drg.node.VectorSourceNode;
import com.trading.drg.api.ScalarValue;

import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * Registry mapping {@link NodeType}s to their respective instantiation
 * factories.
 */
public final class NodeRegistry {

    /** Metadata describing a node's required inputs and its factory. */
    public record NodeMetadata(String[] namedInputs, JsonGraphCompiler.NodeFactory factory) {
    }

    private final Map<NodeType, NodeMetadata> registry = new HashMap<>();

    public NodeRegistry() {
        registerBuiltIns();
    }

    public void registerFactory(NodeType type, JsonGraphCompiler.NodeFactory factory) {
        String[] named = null;
        Class<?> nodeClass = type.getNodeClass();

        if (nodeClass != null) {
            for (Method m : nodeClass.getDeclaredMethods()) {
                if (m.getName().equals("calculate")) {
                    Parameter[] params = m.getParameters();
                    if (params.length > 0) {
                        if (params.length == 1 && params[0].getType().isArray()) {
                            named = null;
                        } else {
                            named = new String[params.length];
                            for (int i = 0; i < params.length; i++) {
                                named[i] = params[i].getName();
                            }
                        }
                        break;
                    }
                }
            }
        }

        registerFactory(type, named, factory);
    }

    public void registerFactory(NodeType type, String[] namedInputs,
            JsonGraphCompiler.NodeFactory factory) {
        registry.put(type, new NodeMetadata(namedInputs, factory));
    }

    public NodeMetadata getMetadata(NodeType type) {
        return registry.get(type);
    }

    // ── Built-in Factories ──────────────────────────────────────────

    private void registerBuiltIns() {
        // --- Fn1 Nodes ---
        registerFactory(NodeType.EWMA, (name, props, deps) -> {
            var fn = new Ewma(JsonGraphCompiler.getDouble(props, "alpha", 0.1));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value()));
        });
        registerFactory(NodeType.DIFF, (name, props, deps) -> {
            var fn = new Diff();
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value()));
        });
        registerFactory(NodeType.HIST_VOL, (name, props, deps) -> {
            var fn = new HistVol(JsonGraphCompiler.getInt(props, "window", 10));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value()));
        });
        registerFactory(NodeType.LOG_RETURN, (name, props, deps) -> {
            var fn = new LogReturn();
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value()));
        });
        registerFactory(NodeType.MACD, (name, props, deps) -> {
            var fn = new Macd(JsonGraphCompiler.getInt(props, "fast", 12),
                    JsonGraphCompiler.getInt(props, "slow", 26));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value()));
        });
        registerFactory(NodeType.ROLLING_MAX, (name, props, deps) -> {
            var fn = new RollingMax(JsonGraphCompiler.getInt(props, "window", 10));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value()));
        });
        registerFactory(NodeType.ROLLING_MIN, (name, props, deps) -> {
            var fn = new RollingMin(JsonGraphCompiler.getInt(props, "window", 10));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value()));
        });
        registerFactory(NodeType.RSI, (name, props, deps) -> {
            var fn = new Rsi(JsonGraphCompiler.getInt(props, "window", 14));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value()));
        });
        registerFactory(NodeType.SMA, (name, props, deps) -> {
            var fn = new Sma(JsonGraphCompiler.getInt(props, "window", 10));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value()));
        });
        registerFactory(NodeType.Z_SCORE, (name, props, deps) -> {
            var fn = new ZScore(JsonGraphCompiler.getInt(props, "window", 20));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value()));
        });

        // --- Fn2 Nodes ---
        registerFactory(NodeType.SPREAD, (name, props, deps) -> {
            var fn = new Spread();
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value(), ((ScalarValue) deps[1]).value()));
        });
        registerFactory(NodeType.BETA, (name, props, deps) -> {
            var fn = new Beta(JsonGraphCompiler.getInt(props, "window", 20));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value(), ((ScalarValue) deps[1]).value()));
        });
        registerFactory(NodeType.CORRELATION, (name, props, deps) -> {
            var fn = new Correlation(JsonGraphCompiler.getInt(props, "window", 20));
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value(), ((ScalarValue) deps[1]).value()));
        });
        registerFactory(NodeType.TRI_ARB_SPREAD, (name, props, deps) -> {
            var fn = new TriangularArbSpread();
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value(),
                            ((ScalarValue) deps[1]).value(), ((ScalarValue) deps[2]).value()));
        });

        // --- FnN Nodes ---
        registerFactory(NodeType.HARMONIC_MEAN, (name, props, deps) -> {
            var fn = new HarmonicMean();
            double[] scratch = new double[deps.length];
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
                for (int i = 0; i < deps.length; i++)
                    scratch[i] = ((ScalarValue) deps[i]).value();
                return fn.apply(scratch);
            });
        });
        registerFactory(NodeType.WEIGHTED_AVG, (name, props, deps) -> {
            var fn = new WeightedAverage();
            double[] scratch = new double[deps.length];
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
                for (int i = 0; i < deps.length; i++)
                    scratch[i] = ((ScalarValue) deps[i]).value();
                return fn.apply(scratch);
            });
        });
        registerFactory(NodeType.AVERAGE, (name, props, deps) -> {
            var fn = new Average();
            double[] scratch = new double[deps.length];
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
                for (int i = 0; i < deps.length; i++)
                    scratch[i] = ((ScalarValue) deps[i]).value();
                return fn.apply(scratch);
            });
        });

        // --- Source / Structural Nodes ---
        registerFactory(NodeType.SCALAR_SOURCE, (name, props, deps) -> {
            return new ScalarSourceNode(name, JsonGraphCompiler.getDouble(props, "value", 0.0),
                    JsonGraphCompiler.parseCutoff(props));
        });
        registerFactory(NodeType.VECTOR_SOURCE, (name, props, deps) -> {
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
        });
        registerFactory(NodeType.VECTOR_ELEMENT, (name, props, deps) -> {
            int index = JsonGraphCompiler.getInt(props, "index", 0);
            return com.trading.drg.dsl.GraphBuilder.create().vectorElement(name,
                    (com.trading.drg.api.VectorValue) deps[0], index);
        });
        registerFactory(NodeType.COMPUTE_VECTOR, (name, props, deps) -> {
            int size = JsonGraphCompiler.getInt(props, "size", -1);
            double tolerance = JsonGraphCompiler.getDouble(props, "tolerance", 1e-15);
            return com.trading.drg.dsl.GraphBuilder.create().computeVector(name, size, tolerance,
                    (inNodes, out) -> {
                        if (inNodes.length > 0 && inNodes[0] instanceof com.trading.drg.api.VectorValue v) {
                            for (int i = 0; i < Math.min(size, v.size()); i++)
                                out[i] = v.valueAt(i);
                        }
                    }, deps);
        });
    }
}
