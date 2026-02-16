package com.trading.drg.io;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;

import java.util.*;

import com.trading.drg.api.*;
import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.node.VectorSourceNode;
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
        var nodeDefs = graphInfo.getNodes();
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
            if (nd.isSource())
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
