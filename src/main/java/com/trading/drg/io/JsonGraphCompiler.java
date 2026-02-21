package com.trading.drg.io;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;

import java.util.*;

import com.trading.drg.api.*;
import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.node.VectorSourceNode;
import com.trading.drg.node.ScalarCalcNode;
import com.trading.drg.util.ScalarCutoffs;

/**
 * Compiles a JSON {@link GraphDefinition} into a live, executable graph.
 *
 * <p>
 * This compiler operates in two passes:
 * <ol>
 * <li><b>Instantiation:</b> Creates all nodes based on registered factories
 * (types).</li>
 * <li><b>Wiring:</b> Connects dependencies based on the topology.</li>
 * </ol>
 *
 * <p>
 * Supports plugins via {@link #registerFactory(String, NodeFactory)}.
 */
public final class JsonGraphCompiler {
    private final Map<String, NodeFactory> factories = new HashMap<>();

    /**
     * Registers a factory for a specific node type string.
     */
    public JsonGraphCompiler registerFactory(String type, NodeFactory f) {
        factories.put(type, f);
        return this;
    }

    /** Register built-in factories (double_source, vector_source). */
    public JsonGraphCompiler registerBuiltIns() {
        // --- Fn1 Nodes ---
        registerFactory("ewma", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Ewma(getDouble(props, "alpha", 0.1));
            return new ScalarCalcNode(name, parseCutoff(props), () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory("diff", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Diff();
            return new ScalarCalcNode(name, parseCutoff(props), () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory("hist_vol", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.HistVol(getInt(props, "window", 10));
            return new ScalarCalcNode(name, parseCutoff(props), () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory("log_return", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.LogReturn();
            return new ScalarCalcNode(name, parseCutoff(props), () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory("macd", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Macd(getInt(props, "fast", 12), getInt(props, "slow", 26));
            return new ScalarCalcNode(name, parseCutoff(props), () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory("rolling_max", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.RollingMax(getInt(props, "window", 10));
            return new ScalarCalcNode(name, parseCutoff(props), () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory("rolling_min", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.RollingMin(getInt(props, "window", 10));
            return new ScalarCalcNode(name, parseCutoff(props), () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory("rsi", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Rsi(getInt(props, "window", 14));
            return new ScalarCalcNode(name, parseCutoff(props), () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory("sma", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Sma(getInt(props, "window", 10));
            return new ScalarCalcNode(name, parseCutoff(props), () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        registerFactory("z_score", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.ZScore(getInt(props, "window", 20));
            return new ScalarCalcNode(name, parseCutoff(props), () -> fn.apply(((ScalarValue) deps[0]).doubleValue()));
        });

        // --- Fn2 Nodes ---
        registerFactory("beta", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Beta(getInt(props, "window", 20));
            return new ScalarCalcNode(name, parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()));
        });

        registerFactory("correlation", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Correlation(getInt(props, "window", 20));
            return new ScalarCalcNode(name, parseCutoff(props),
                    () -> fn.apply(((ScalarValue) deps[0]).doubleValue(), ((ScalarValue) deps[1]).doubleValue()));
        });

        // --- Fn3 Nodes ---
        registerFactory("tri_arb_spread", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.TriangularArbSpread();
            return new ScalarCalcNode(name, parseCutoff(props), () -> fn.apply(((ScalarValue) deps[0]).doubleValue(),
                    ((ScalarValue) deps[1]).doubleValue(), ((ScalarValue) deps[2]).doubleValue()));
        });

        // --- FnN Nodes ---
        registerFactory("harmonic_mean", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.HarmonicMean();
            double[] scratch = new double[deps.length];
            return new ScalarCalcNode(name, parseCutoff(props), () -> {
                for (int i = 0; i < deps.length; i++)
                    scratch[i] = ((ScalarValue) deps[i]).doubleValue();
                return fn.apply(scratch);
            });
        });

        registerFactory("weighted_avg", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.WeightedAverage();
            double[] scratch = new double[deps.length];
            return new ScalarCalcNode(name, parseCutoff(props), () -> {
                for (int i = 0; i < deps.length; i++)
                    scratch[i] = ((ScalarValue) deps[i]).doubleValue();
                return fn.apply(scratch);
            });
        });

        registerFactory("average", (name, props, deps) -> {
            var fn = new com.trading.drg.fn.finance.Average();
            double[] scratch = new double[deps.length];
            return new ScalarCalcNode(name, parseCutoff(props), () -> {
                for (int i = 0; i < deps.length; i++)
                    scratch[i] = ((ScalarValue) deps[i]).doubleValue();
                return fn.apply(scratch);
            });
        });

        registerFactory("scalar_source", (name, props, deps) -> {
            double init = getDouble(props, "initial_value", Double.NaN);
            ScalarCutoff cutoff = parseCutoff(props);
            return new ScalarSourceNode(name, init, cutoff);
        });
        registerFactory("double_source", (name, props, deps) -> {
            double init = getDouble(props, "initial_value", Double.NaN);
            ScalarCutoff cutoff = parseCutoff(props);
            return new ScalarSourceNode(name, init, cutoff);
        });
        registerFactory("vector_source", (name, props, deps) -> {
            int size = getInt(props, "size", -1);
            if (size <= 0)
                throw new IllegalArgumentException("vector_source needs positive 'size'");
            return new VectorSourceNode(name, size, getDouble(props, "tolerance", 1e-15));
        });
        return this;
    }

    /**
     * Compiles the definition into a graph.
     * 
     * @param def The graph definition.
     * @return A container holding the engine and name lookup map.
     */
    public CompiledGraph compile(GraphDefinition def) {
        var graphInfo = def.getGraph();

        // 0. Pre-process templates
        Map<String, GraphDefinition.TemplateDef> templateMap = new HashMap<>();
        if (graphInfo.getTemplates() != null) {
            for (var t : graphInfo.getTemplates()) {
                templateMap.put(t.getName(), t);
            }
        }

        var nodeDefs = expandTemplates(graphInfo.getNodes(), templateMap);

        // 1. Sort dependencies topologically
        nodeDefs = topologicalSort(nodeDefs);

        Map<String, Node<?>> nodesByName = new HashMap<>(nodeDefs.size() * 2);
        var topo = TopologicalOrder.builder();

        // 2. Instantiate and Build Topology
        for (var nd : nodeDefs) {
            NodeFactory f = factories.get(nd.getType());
            if (f == null)
                throw new IllegalArgumentException("No factory for type: " + nd.getType());

            Node<?>[] deps = new Node<?>[0];
            if (nd.getDependencies() != null) {
                deps = new Node<?>[nd.getDependencies().size()];
                for (int i = 0; i < deps.length; i++) {
                    deps[i] = nodesByName.get(nd.getDependencies().get(i));
                }
            }

            Node<?> node = f.create(nd.getName(),
                    nd.getProperties() != null ? nd.getProperties() : Collections.emptyMap(),
                    deps);
            nodesByName.put(nd.getName(), node);

            topo.addNode(node);
            if (nd.isSource() || node instanceof SourceNode)
                topo.markSource(nd.getName());

            if (nd.getDependencies() != null) {
                for (String dep : nd.getDependencies()) {
                    topo.addEdge(dep, nd.getName());
                }
            }
        }

        return new CompiledGraph(graphInfo.getName(), graphInfo.getVersion(),
                new StabilizationEngine(topo.build()), nodesByName);
    }

    private List<GraphDefinition.NodeDef> topologicalSort(List<GraphDefinition.NodeDef> nodes) {
        Map<String, GraphDefinition.NodeDef> byName = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> outEdges = new HashMap<>();

        for (var n : nodes) {
            byName.put(n.getName(), n);
            inDegree.put(n.getName(), 0);
            outEdges.put(n.getName(), new ArrayList<>());
        }

        for (var n : nodes) {
            if (n.getDependencies() != null) {
                for (String dep : n.getDependencies()) {
                    if (!byName.containsKey(dep)) {
                        throw new IllegalArgumentException("Unknown dependency: " + dep + " for node: " + n.getName());
                    }
                    outEdges.get(dep).add(n.getName());
                    inDegree.put(n.getName(), inDegree.get(n.getName()) + 1);
                }
            }
        }

        Queue<String> q = new ArrayDeque<>();
        for (var e : inDegree.entrySet()) {
            if (e.getValue() == 0)
                q.add(e.getKey());
        }

        List<GraphDefinition.NodeDef> sorted = new ArrayList<>();
        while (!q.isEmpty()) {
            String curr = q.poll();
            sorted.add(byName.get(curr));
            for (String child : outEdges.get(curr)) {
                int deg = inDegree.get(child) - 1;
                inDegree.put(child, deg);
                if (deg == 0)
                    q.add(child);
            }
        }

        if (sorted.size() != nodes.size()) {
            throw new IllegalStateException("Cycle detected in JSON graph definition");
        }
        return sorted;
    }

    static ScalarCutoff parseCutoff(Map<String, Object> props) {
        Object o = props.get("cutoff");
        if (o == null)
            return ScalarCutoffs.EXACT;
        if (o instanceof String s)
            return switch (s) {
                case "exact" -> ScalarCutoffs.EXACT;
                case "always" -> ScalarCutoffs.ALWAYS;
                case "never" -> ScalarCutoffs.NEVER;
                case "absolute" -> ScalarCutoffs.absoluteTolerance(getDouble(props, "tolerance", 1e-15));
                case "relative" -> ScalarCutoffs.relativeTolerance(getDouble(props, "tolerance", 1e-10));
                default -> throw new IllegalArgumentException("Unknown cutoff: " + s);
            };
        return ScalarCutoffs.EXACT;
    }

    static double getDouble(Map<String, Object> props, String key, double def) {
        Object v = props.get(key);
        if (v == null)
            return def;
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
    }

    static int getInt(Map<String, Object> props, String key, int def) {
        Object v = props.get(key);
        if (v == null)
            return def;
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }

    @FunctionalInterface
    public interface NodeFactory {
        Node<?> create(String name, Map<String, Object> properties, Node<?>[] dependencies);
    }

    /**
     * The result of compilation: a graph engine ready to run.
     */
    private List<GraphDefinition.NodeDef> expandTemplates(List<GraphDefinition.NodeDef> nodes,
            Map<String, GraphDefinition.TemplateDef> templates) {
        List<GraphDefinition.NodeDef> expanded = new ArrayList<>();
        Queue<GraphDefinition.NodeDef> queue = new ArrayDeque<>(nodes);

        while (!queue.isEmpty()) {
            var node = queue.poll();
            if ("template".equals(node.getType())) {
                // Expand
                String templateName = (String) node.getProperties().get("template");
                if (templateName == null)
                    throw new IllegalArgumentException("Template node missing 'template' property: " + node.getName());

                var template = templates.get(templateName);
                if (template == null)
                    throw new IllegalArgumentException("Unknown template: " + templateName);

                Map<String, Object> params = node.getProperties();

                for (var tNode : template.getNodes()) {
                    var newNode = deepCopy(tNode);
                    // Substitute variables
                    newNode.setName(substitute(newNode.getName(), params));
                    if (newNode.getDependencies() != null) {
                        newNode.setDependencies(newNode.getDependencies().stream()
                                .map(d -> substitute(d, params))
                                .collect(java.util.stream.Collectors.toList()));
                    }
                    if (newNode.getProperties() != null) {
                        Map<String, Object> newProps = new HashMap<>();
                        for (var entry : newNode.getProperties().entrySet()) {
                            Object val = entry.getValue();
                            if (val instanceof String s) {
                                newProps.put(entry.getKey(), substitute(s, params));
                            } else {
                                newProps.put(entry.getKey(), val);
                            }
                        }
                        newNode.setProperties(newProps);
                    }
                    queue.add(newNode); // Add to queue to handle nested templates
                }
            } else {
                expanded.add(node);
            }
        }
        return expanded;
    }

    private String substitute(String s, Map<String, Object> params) {
        if (s == null || !s.contains("{{"))
            return s;
        for (var entry : params.entrySet()) {
            String key = "{{" + entry.getKey() + "}}";
            if (s.contains(key)) {
                s = s.replace(key, String.valueOf(entry.getValue()));
            }
        }
        return s;
    }

    private GraphDefinition.NodeDef deepCopy(GraphDefinition.NodeDef original) {
        var copy = new GraphDefinition.NodeDef();
        copy.setName(original.getName());
        copy.setType(original.getType());
        copy.setSource(original.isSource());
        if (original.getDependencies() != null) {
            copy.setDependencies(new ArrayList<>(original.getDependencies()));
        }
        if (original.getProperties() != null) {
            copy.setProperties(new HashMap<>(original.getProperties()));
        }
        return copy;
    }

    /**
     * The result of compilation: a graph engine ready to run.
     */
    public static final class CompiledGraph {
        private final String name, version;
        private final StabilizationEngine engine;
        private final Map<String, Node<?>> nodesByName;

        CompiledGraph(String name, String version, StabilizationEngine engine, Map<String, Node<?>> nodesByName) {
            this.name = name;
            this.version = version;
            this.engine = engine;
            this.nodesByName = Collections.unmodifiableMap(nodesByName);
        }

        public String name() {
            return name;
        }

        public String version() {
            return version;
        }

        public StabilizationEngine engine() {
            return engine;
        }

        public Map<String, Node<?>> nodesByName() {
            return nodesByName;
        }
    }
}
