package com.trading.drg.io;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;

import java.util.*;

import com.trading.drg.api.*;
import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.node.VectorSourceNode;
import com.trading.drg.node.GenericFn1Node;
import com.trading.drg.node.GenericFn2Node;
import com.trading.drg.node.GenericFn3Node;
import com.trading.drg.node.GenericFnNNode;
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
        // Register common finance nodes using GENERIC adapters

        // --- Fn1 Nodes ---
        registerFactory("ewma", (name, props) -> new GenericFn1Node(name,
                new com.trading.drg.fn.finance.Ewma(getDouble(props, "alpha", 0.1)), parseCutoff(props)));

        registerFactory("diff",
                (name, props) -> new GenericFn1Node(name, new com.trading.drg.fn.finance.Diff(), parseCutoff(props)));

        registerFactory("hist_vol", (name, props) -> new GenericFn1Node(name,
                new com.trading.drg.fn.finance.HistVol(getInt(props, "window", 10)), parseCutoff(props)));

        registerFactory("log_return", (name, props) -> new GenericFn1Node(name,
                new com.trading.drg.fn.finance.LogReturn(), parseCutoff(props)));

        registerFactory("macd", (name, props) -> new GenericFn1Node(name, new com.trading.drg.fn.finance.Macd(
                getInt(props, "fast", 12),
                getInt(props, "slow", 26)), parseCutoff(props)));

        registerFactory("rolling_max", (name, props) -> new GenericFn1Node(name,
                new com.trading.drg.fn.finance.RollingMax(getInt(props, "window", 10)), parseCutoff(props)));

        registerFactory("rolling_min", (name, props) -> new GenericFn1Node(name,
                new com.trading.drg.fn.finance.RollingMin(getInt(props, "window", 10)), parseCutoff(props)));

        registerFactory("rsi", (name, props) -> new GenericFn1Node(name,
                new com.trading.drg.fn.finance.Rsi(getInt(props, "window", 14)), parseCutoff(props)));

        registerFactory("sma", (name, props) -> new GenericFn1Node(name,
                new com.trading.drg.fn.finance.Sma(getInt(props, "window", 10)), parseCutoff(props)));

        registerFactory("z_score", (name, props) -> new GenericFn1Node(name,
                new com.trading.drg.fn.finance.ZScore(getInt(props, "window", 20)), parseCutoff(props)));

        // --- Fn2 Nodes ---
        registerFactory("beta", (name, props) -> new GenericFn2Node(name,
                new com.trading.drg.fn.finance.Beta(getInt(props, "window", 20)), parseCutoff(props)));

        registerFactory("correlation", (name, props) -> new GenericFn2Node(name,
                new com.trading.drg.fn.finance.Correlation(getInt(props, "window", 20)), parseCutoff(props)));

        // --- Fn3 Nodes ---
        registerFactory("tri_arb_spread", (name, props) -> new GenericFn3Node(name,
                new com.trading.drg.fn.finance.TriangularArbSpread(), parseCutoff(props)));

        // --- FnN Nodes ---
        registerFactory("harmonic_mean", (name, props) -> new GenericFnNNode(name,
                new com.trading.drg.fn.finance.HarmonicMean(), parseCutoff(props)));

        registerFactory("weighted_avg", (name, props) -> new GenericFnNNode(name,
                new com.trading.drg.fn.finance.WeightedAverage(), parseCutoff(props)));

        registerFactory("average", (name, props) -> new GenericFnNNode(name,
                new com.trading.drg.fn.finance.Average(), parseCutoff(props)));

        registerFactory("scalar_source", (name, props) -> {
            double init = getDouble(props, "initial_value", 0.0);
            ScalarCutoff cutoff = parseCutoff(props);
            return new ScalarSourceNode(name, init, cutoff);
        });
        registerFactory("double_source", (name, props) -> {
            double init = getDouble(props, "initial_value", 0.0);
            ScalarCutoff cutoff = parseCutoff(props);
            return new ScalarSourceNode(name, init, cutoff);
        });
        registerFactory("vector_source", (name, props) -> {
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
        Map<String, Node<?>> nodesByName = new HashMap<>(nodeDefs.size() * 2);

        // 1. Create nodes
        for (var nd : nodeDefs) {
            NodeFactory f = factories.get(nd.getType());
            if (f == null)
                throw new IllegalArgumentException("No factory for type: " + nd.getType());
            Node<?> node = f.create(nd.getName(),
                    nd.getProperties() != null ? nd.getProperties() : Collections.emptyMap());
            nodesByName.put(nd.getName(), node);
        }

        // 2. Build Topology
        var topo = TopologicalOrder.builder();
        for (var nd : nodeDefs) {
            topo.addNode(nodesByName.get(nd.getName()));
            if (nd.isSource() || nodesByName.get(nd.getName()) instanceof SourceNode)
                topo.markSource(nd.getName());
        }
        for (var nd : nodeDefs) {
            if (nd.getDependencies() != null)
                for (String dep : nd.getDependencies())
                    topo.addEdge(dep, nd.getName());
        }

        // 3. Inject Dependencies (for nodes that need them post-creation)
        for (var nd : nodeDefs) {
            Node<?> node = nodesByName.get(nd.getName());
            if (node instanceof DependencyInjectable inj && nd.getDependencies() != null) {
                Node<?>[] ups = nd.getDependencies().stream().map(nodesByName::get).toArray(Node<?>[]::new);
                inj.injectDependencies(ups);
            }
        }

        return new CompiledGraph(graphInfo.getName(), graphInfo.getVersion(),
                new StabilizationEngine(topo.build()), nodesByName);
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
        Node<?> create(String name, Map<String, Object> properties);
    }

    /**
     * Marker interface for nodes that need dependencies injected after
     * instantiation.
     */
    public interface DependencyInjectable {
        void injectDependencies(Node<?>[] upstreams);
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
