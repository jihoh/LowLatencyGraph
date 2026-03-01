package com.trading.drg.io;

import com.trading.drg.fn.Fn1;
import com.trading.drg.fn.Fn2;
import com.trading.drg.fn.Fn3;
import com.trading.drg.fn.FnN;
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
import com.trading.drg.api.VectorValue;
import com.trading.drg.dsl.GraphBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
        // --- Fn1 Nodes (1 scalar input) ---
        registerFn1(NodeType.EWMA, p -> new Ewma(JsonGraphCompiler.getDouble(p, "alpha", 0.1)));
        registerFn1(NodeType.DIFF, p -> new Diff());
        registerFn1(NodeType.HIST_VOL, p -> new HistVol(JsonGraphCompiler.getInt(p, "window", 10)));
        registerFn1(NodeType.LOG_RETURN, p -> new LogReturn());
        registerFn1(NodeType.MACD, p -> new Macd(JsonGraphCompiler.getInt(p, "fast", 12),
                JsonGraphCompiler.getInt(p, "slow", 26)));
        registerFn1(NodeType.ROLLING_MAX, p -> new RollingMax(JsonGraphCompiler.getInt(p, "window", 10)));
        registerFn1(NodeType.ROLLING_MIN, p -> new RollingMin(JsonGraphCompiler.getInt(p, "window", 10)));
        registerFn1(NodeType.RSI, p -> new Rsi(JsonGraphCompiler.getInt(p, "window", 14)));
        registerFn1(NodeType.SMA, p -> new Sma(JsonGraphCompiler.getInt(p, "window", 10)));
        registerFn1(NodeType.Z_SCORE, p -> new ZScore(JsonGraphCompiler.getInt(p, "window", 20)));

        // --- Fn2 Nodes (2 scalar inputs) ---
        registerFn2(NodeType.SPREAD, p -> new Spread());
        registerFn2(NodeType.BETA, p -> new Beta(JsonGraphCompiler.getInt(p, "window", 20)));
        registerFn2(NodeType.CORRELATION, p -> new Correlation(JsonGraphCompiler.getInt(p, "window", 20)));

        // --- Fn3 Nodes (3 scalar inputs) ---
        registerFn3(NodeType.TRI_ARB_SPREAD, p -> new TriangularArbSpread());

        // --- FnN Nodes (N scalar inputs) ---
        registerFnN(NodeType.HARMONIC_MEAN, p -> new HarmonicMean());
        registerFnN(NodeType.WEIGHTED_AVG, p -> new WeightedAverage());
        registerFnN(NodeType.AVERAGE, p -> new Average());

        // --- Source / Structural Nodes (unique logic, kept explicit) ---
        registerFactory(NodeType.SCALAR_SOURCE,
                (name, props, deps) -> new ScalarSourceNode(name, JsonGraphCompiler.getDouble(props, "value", 0.0),
                        JsonGraphCompiler.parseCutoff(props)));
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

            if (headersObj instanceof List<?> list) {
                String[] headers = list.stream().map(Object::toString).toArray(String[]::new);
                node.withHeaders(headers);
            }
            return node;
        });
        registerFactory(NodeType.VECTOR_ELEMENT, (name, props, deps) -> {
            int index = JsonGraphCompiler.getInt(props, "index", 0);
            return GraphBuilder.create().vectorElement(name,
                    (VectorValue) deps[0], index);
        });
        registerFactory(NodeType.COMPUTE_VECTOR, (name, props, deps) -> {
            int size = JsonGraphCompiler.getInt(props, "size", -1);
            double tolerance = JsonGraphCompiler.getDouble(props, "tolerance", 1e-15);
            return GraphBuilder.create().computeVector(name, size, tolerance,
                    (inNodes, out) -> {
                        if (inNodes.length > 0 && inNodes[0] instanceof VectorValue v) {
                            for (int i = 0; i < Math.min(size, v.size()); i++)
                                out[i] = v.valueAt(i);
                        }
                    }, deps);
        });
    }

    // ── Helper methods to eliminate Fn registration boilerplate ──────────

    private void registerFn1(NodeType type, Function<Map<String, Object>, Fn1> supplier) {
        registerFactory(type, (name, props, deps) -> {
            var fn = supplier.apply(props);
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value()));
        });
    }

    private void registerFn2(NodeType type, Function<Map<String, Object>, Fn2> supplier) {
        registerFactory(type, (name, props, deps) -> {
            var fn = supplier.apply(props);
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value(), ((ScalarValue) deps[1]).value()));
        });
    }

    private void registerFn3(NodeType type, Function<Map<String, Object>, Fn3> supplier) {
        registerFactory(type, (name, props, deps) -> {
            var fn = supplier.apply(props);
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value(),
                            ((ScalarValue) deps[1]).value(), ((ScalarValue) deps[2]).value()));
        });
    }

    private void registerFnN(NodeType type, Function<Map<String, Object>, FnN> supplier) {
        registerFactory(type, (name, props, deps) -> {
            var fn = supplier.apply(props);
            double[] scratch = new double[deps.length];
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
                for (int i = 0; i < deps.length; i++)
                    scratch[i] = ((ScalarValue) deps[i]).value();
                return fn.apply(scratch);
            });
        });
    }
}
