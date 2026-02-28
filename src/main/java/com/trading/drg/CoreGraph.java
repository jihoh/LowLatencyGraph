package com.trading.drg;

import com.trading.drg.api.Node;
import com.trading.drg.api.StabilizationListener;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.io.GraphDefinition;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.api.ScalarValue;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A high-level wrapper around the DRG engine that simplifies JSON-based graph
 * instantiation and event publishing.
 * <p>
 * Responsibilities: parse JSON, compile graph, update sources, stabilize.
 * Dashboard wiring is handled separately by
 * {@link com.trading.drg.web.DashboardWiring}.
 */
public class CoreGraph {
    private final StabilizationEngine engine;
    private final Map<String, Node<?>> nodes;

    private final String name;
    private final String version;
    private final Map<String, String> logicalTypes;
    private final Map<String, String> descriptions;
    private final List<String> originalOrder;
    private final Map<String, Map<String, String>> edgeLabels;

    private final com.trading.drg.util.CompositeStabilizationListener compositeListener;

    // Cache source nodes for O(1) updates
    private final Node<?>[] sourceNodes;

    /**
     * Creates a new CoreGraph from a JSON file path string.
     */
    public CoreGraph(String jsonPath) {
        this(Path.of(jsonPath));
    }

    /**
     * Creates a new CoreGraph from a JSON file path.
     */
    public CoreGraph(Path jsonPath) {
        GraphDefinition graphDef;
        try {
            ObjectMapper mapper = new ObjectMapper();
            graphDef = mapper.readValue(jsonPath.toFile(), GraphDefinition.class);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to load graph definition from " + jsonPath, e);
        }
        var compiler = new JsonGraphCompiler();
        var compiled = compiler.compile(graphDef);

        var info = graphDef.getGraph();
        this.name = info != null ? info.getName() : "Unknown";
        this.version = compiled.version();

        this.engine = compiled.engine();
        this.nodes = compiled.nodesByName();
        this.logicalTypes = compiled.logicalTypes();
        this.descriptions = compiled.descriptions();
        this.originalOrder = compiled.originalOrder();
        this.edgeLabels = compiled.edgeLabels();

        // Pre-cache source nodes for update()
        var topology = engine.topology();
        this.sourceNodes = new Node<?>[topology.nodeCount()];
        for (int i = 0; i < topology.nodeCount(); i++) {
            if (topology.isSource(i)) {
                sourceNodes[i] = topology.node(i);
            }
        }

        // Register composite listener
        this.compositeListener = new com.trading.drg.util.CompositeStabilizationListener();
        this.engine.setListener(this.compositeListener);
    }

    // ── Listeners ────────────────────────────────────────────────

    /**
     * Adds a listener to the composite (does not overwrite existing ones).
     */
    public void setListener(StabilizationListener listener) {
        compositeListener.addForComposite(listener);
    }

    // ── Accessors ────────────────────────────────────────────────

    public StabilizationEngine getEngine() {
        return engine;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, Node<?>> getNodes() {
        return nodes;
    }

    public Map<String, String> getLogicalTypes() {
        return logicalTypes;
    }

    public Map<String, String> getDescriptions() {
        return descriptions;
    }

    public List<String> getOriginalOrder() {
        return originalOrder;
    }

    public Map<String, Map<String, String>> getEdgeLabels() {
        return edgeLabels;
    }

    // ── Domain Operations ────────────────────────────────────────

    /**
     * Reads the current value of a Scalar node.
     */
    public double getDouble(String name) {
        Node<?> node = getNode(name);
        if (node instanceof ScalarValue sv) {
            return sv.doubleValue();
        }
        throw new IllegalArgumentException("Node " + name + " is not a ScalarValue.");
    }

    /**
     * Retrieves a node by name.
     */
    @SuppressWarnings("unchecked")
    public <T> T getNode(String name) {
        return (T) nodes.get(name);
    }

    /**
     * Resolves a node name to its topological index.
     */
    public int getNodeId(String name) {
        return engine.topology().topoIndex(name);
    }

    /**
     * Updates a Scalar source node by name. Does NOT trigger stabilization.
     */
    public void update(String nodeName, double value) {
        update(getNodeId(nodeName), value);
    }

    /**
     * Updates a Scalar source node by ID. Does NOT trigger stabilization.
     *
     * @throws IllegalArgumentException if {@code nodeId} is out of range or does
     *                                  not refer to a scalar source node.
     */
    public void update(int nodeId, double value) {
        if (nodeId < 0 || nodeId >= sourceNodes.length) {
            throw new IllegalArgumentException(
                    "nodeId " + nodeId + " is out of range [0, " + sourceNodes.length + ")");
        }
        Node<?> node = sourceNodes[nodeId];
        if (node instanceof com.trading.drg.node.ScalarSourceNode sn) {
            sn.updateDouble(value);
            engine.markDirty(nodeId);
        } else {
            throw new IllegalArgumentException(
                    "Node at id " + nodeId + " is not a scalar source node" +
                    (node != null ? " (it is: " + node.name() + ")" : " (not a source node)"));
        }
    }

    /**
     * Updates a single element of a Vector source node by ID.
     *
     * @throws IllegalArgumentException if {@code nodeId} is out of range or does
     *                                  not refer to a vector source node.
     */
    public void update(int nodeId, int index, double value) {
        if (nodeId < 0 || nodeId >= sourceNodes.length) {
            throw new IllegalArgumentException(
                    "nodeId " + nodeId + " is out of range [0, " + sourceNodes.length + ")");
        }
        Node<?> node = sourceNodes[nodeId];
        if (node instanceof com.trading.drg.node.VectorSourceNode vsn) {
            vsn.updateAt(index, value);
            engine.markDirty(nodeId);
        } else {
            throw new IllegalArgumentException(
                    "Node at id " + nodeId + " is not a vector source node" +
                    (node != null ? " (it is: " + node.name() + ")" : " (not a source node)"));
        }
    }

    /**
     * Triggers a stabilization cycle.
     *
     * @return Number of nodes recomputed.
     */
    public int stabilize() {
        return engine.stabilize();
    }
}
