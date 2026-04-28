package com.trading.drg.io;

import com.trading.drg.fn.Fn1;
import com.trading.drg.fn.Fn2;
import com.trading.drg.fn.Fn3;
import com.trading.drg.fn.FnN;
import com.trading.drg.fn.VectorMathFn;
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
import com.trading.drg.node.BooleanNode;
import com.trading.drg.node.SwitchNode;
import com.trading.drg.node.VectorCalcNode;

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
import java.util.Arrays;

/**
 * Registry mapping {@link NodeType}s to their respective instantiation
 * factories.
 */
public final class NodeRegistry {

    /**
     * Metadata describing a node's required inputs and its factory.
     * {@code namedInputs} is an immutable list, or {@code null} for unbounded FnN nodes.
     */
    public record NodeMetadata(List<String> namedInputs, JsonGraphCompiler.NodeFactory factory) {
    }

    private final Map<String, NodeMetadata> registry = new HashMap<>();

    public NodeRegistry() {
        registerBuiltIns();
    }

    /**
     * Installs a {@link NodeProvider}, registering all of its custom
     * node types into this registry.
     *
     * @param provider the node provider to install
     */
    /**
     * Installs a {@link NodeProvider} and returns {@code this} for fluent chaining.
     *
     * <pre>{@code
     * compiler.getRegistry()
     *     .install(new AlphaProvider())
     *     .install(new BetaProvider());
     * }</pre>
     */
    public NodeRegistry install(NodeProvider provider) {
        provider.register(this);
        return this;
    }

    private void registerFactory(NodeType type, JsonGraphCompiler.NodeFactory factory) {
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

        registerFactory(type.name(), named, factory);
    }

    private void registerFactory(NodeType type, String[] namedInputs,
            JsonGraphCompiler.NodeFactory factory) {
        registerFactory(type.name(), namedInputs, factory);
    }

    public void registerFactory(String type, String[] namedInputs,
            JsonGraphCompiler.NodeFactory factory) {
        String key = type.toUpperCase();
        if (registry.containsKey(key)) {
            throw new IllegalStateException(
                    "Node type already registered: '" + key + "'. " +
                    "Use a unique type name or remove the duplicate NodeProvider.");
        }
        List<String> inputs = (namedInputs == null) ? null : List.of(namedInputs);
        registry.put(key, new NodeMetadata(inputs, factory));
    }

    public NodeMetadata getMetadata(String type) {
        return registry.get(type.toUpperCase());
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
            VectorSourceNode node = new VectorSourceNode(name, size, tolerance);

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

        // --- High-Performance Special Nodes ---
        registerFactory(NodeType.THROTTLE, new String[] { "input" }, (name, props, deps) -> {
            long windowMs = (long) JsonGraphCompiler.getDouble(props, "windowMs", 100.0);
            return GraphBuilder.create().throttle(name, (ScalarValue) deps[0], windowMs);
        });

        registerFactory(NodeType.TIME_DECAY, new String[] { "input" }, (name, props, deps) -> {
            long halfLifeMs = (long) JsonGraphCompiler.getDouble(props, "halfLifeMs", 100.0);
            return GraphBuilder.create().timeDecay(name, (ScalarValue) deps[0], halfLifeMs);
        });

        registerFactory(NodeType.CONDITION, new String[] { "input" }, (name, props, deps) -> {
            String op = props.getOrDefault("operator", ">").toString();
            double threshold = JsonGraphCompiler.getDouble(props, "threshold", 0.0);
            ScalarValue input = (ScalarValue) deps[0];

            return GraphBuilder.create().condition(name, input, v -> {
                return switch (op) {
                    case ">" -> v > threshold;
                    case "<" -> v < threshold;
                    case ">=" -> v >= threshold;
                    case "<=" -> v <= threshold;
                    case "==" -> v == threshold;
                    case "!=" -> v != threshold;
                    default -> throw new IllegalArgumentException("Unknown operator: " + op);
                };
            });
        });

        registerFactory(NodeType.SWITCH, new String[] { "input", "condition" }, (name, props, deps) -> {
            ScalarValue input = (ScalarValue) deps[0];
            BooleanNode condition = (BooleanNode) deps[1];

            SwitchNode switchNode = new SwitchNode(name, input, condition);

            if (props.containsKey("true_branch")) {
                switchNode.addTrueBranch(props.get("true_branch").toString());
            }
            if (props.containsKey("false_branch")) {
                switchNode.addFalseBranch(props.get("false_branch").toString());
            }
            return switchNode;
        });
    }

    // ── Helper methods to eliminate Fn registration boilerplate ──────────

    private JsonGraphCompiler.NodeFactory fn1Factory(Function<Map<String, Object>, Fn1> supplier) {
        return (name, props, deps) -> {
            Fn1 fn = supplier.apply(props);
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value()));
        };
    }

    private void registerFn1(NodeType type, Function<Map<String, Object>, Fn1> supplier) {
        registerFactory(type, fn1Factory(supplier));
    }

    public void registerFn1(String type, String[] namedInputs, Function<Map<String, Object>, Fn1> supplier) {
        registerFactory(type, namedInputs, fn1Factory(supplier));
    }

    private JsonGraphCompiler.NodeFactory fn2Factory(Function<Map<String, Object>, Fn2> supplier) {
        return (name, props, deps) -> {
            Fn2 fn = supplier.apply(props);
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value(), ((ScalarValue) deps[1]).value()));
        };
    }

    private void registerFn2(NodeType type, Function<Map<String, Object>, Fn2> supplier) {
        registerFactory(type, fn2Factory(supplier));
    }

    public void registerFn2(String type, String[] namedInputs, Function<Map<String, Object>, Fn2> supplier) {
        registerFactory(type, namedInputs, fn2Factory(supplier));
    }

    private JsonGraphCompiler.NodeFactory fn3Factory(Function<Map<String, Object>, Fn3> supplier) {
        return (name, props, deps) -> {
            Fn3 fn = supplier.apply(props);
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).value(),
                            ((ScalarValue) deps[1]).value(), ((ScalarValue) deps[2]).value()));
        };
    }

    private void registerFn3(NodeType type, Function<Map<String, Object>, Fn3> supplier) {
        registerFactory(type, fn3Factory(supplier));
    }

    public void registerFn3(String type, String[] namedInputs, Function<Map<String, Object>, Fn3> supplier) {
        registerFactory(type, namedInputs, fn3Factory(supplier));
    }

    private JsonGraphCompiler.NodeFactory fnNFactory(Function<Map<String, Object>, FnN> supplier) {
        return (name, props, deps) -> {
            FnN fn = supplier.apply(props);
            double[] scratch = new double[deps.length];
            return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props), () -> {
                for (int i = 0; i < deps.length; i++)
                    scratch[i] = ((ScalarValue) deps[i]).value();
                return fn.apply(scratch);
            });
        };
    }

    private void registerFnN(NodeType type, Function<Map<String, Object>, FnN> supplier) {
        registerFactory(type, fnNFactory(supplier));
    }

    public void registerFnN(String type, String[] namedInputs, Function<Map<String, Object>, FnN> supplier) {
        registerFactory(type, namedInputs, fnNFactory(supplier));
    }

    private JsonGraphCompiler.NodeFactory vectorMathFactory(Function<Map<String, Object>, VectorMathFn> supplier) {
        return (name, props, deps) -> {
            int size = JsonGraphCompiler.getInt(props, "size", 0);
            if (size <= 0)
                throw new IllegalArgumentException(
                        "VectorMathFn node '" + name + "' requires positive 'size' property");
            double tolerance = JsonGraphCompiler.getDouble(props, "tolerance", 1e-15);

            VectorMathFn fn = supplier.apply(props);

            int inputDim = 0;
            for (com.trading.drg.api.Node in : deps) {
                if (in instanceof VectorValue v) {
                    inputDim += v.size();
                } else if (in instanceof ScalarValue) {
                    inputDim += 1;
                }
            }

            // Build scratch buffer once; reused every stabilization cycle.
            final double[] scratch = new double[inputDim];
            return new VectorCalcNode(name, size, tolerance, (inNodes, out) -> {
                int idx = 0;
                for (com.trading.drg.api.Node in : deps) {
                    if (in instanceof VectorValue v) {
                        for (int i = 0; i < v.size(); i++) {
                            scratch[idx++] = v.valueAt(i);
                        }
                    } else if (in instanceof ScalarValue s) {
                        scratch[idx++] = s.value();
                    }
                }
                fn.apply(scratch, out);
            }, deps);
        };
    }

    private void registerVectorMathFn(NodeType type, Function<Map<String, Object>, VectorMathFn> supplier) {
        registerFactory(type, vectorMathFactory(supplier));
    }

    public void registerVectorMathFn(String type, String[] namedInputs,
            Function<Map<String, Object>, VectorMathFn> supplier) {
        registerFactory(type, namedInputs, vectorMathFactory(supplier));
    }
}
