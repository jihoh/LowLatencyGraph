package com.trading.drg;

import com.trading.drg.api.Node;
import com.trading.drg.api.StabilizationListener;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.engine.TopologicalOrder;
import com.trading.drg.io.GraphDefinition;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.api.ScalarValue;
import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.node.VectorSourceNode;
import com.trading.drg.util.CompositeStabilizationListener;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * High-level graph engine wrapper for JSON graph instantiation and event
 * publishing.
 */
@Getter
public class CoreGraph {
    private final StabilizationEngine engine;
    private final Map<String, Node> nodes;

    private final String name;
    private final String version;
    private final Map<String, String> logicalTypes;
    private final Map<String, String> descriptions;
    private final List<String> originalOrder;
    private final Map<String, Map<String, String>> edgeLabels;

    private final CompositeStabilizationListener compositeListener;

    // Cache source nodes for O(1) updates
    private final Node[] sourceNodes;

    /** Creates a new CoreGraph from a JSON file path string. */
    public CoreGraph(String jsonPath) {
        this(Path.of(jsonPath));
    }

    /** Creates a new CoreGraph from a JSON file path. */
    public CoreGraph(Path jsonPath) {
        GraphDefinition graphDef;
        try {
            ObjectMapper mapper = new ObjectMapper();
            graphDef = mapper.readValue(jsonPath.toFile(), GraphDefinition.class);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to load graph definition from " + jsonPath, e);
        }
        JsonGraphCompiler compiler = new JsonGraphCompiler();
        JsonGraphCompiler.CompiledGraph compiled = compiler.compile(graphDef);

        GraphDefinition.GraphInfo info = graphDef.getGraph();
        this.name = info != null ? info.getName() : "Unknown";
        this.version = compiled.version();

        this.engine = compiled.engine();
        this.nodes = compiled.nodesByName();
        this.logicalTypes = compiled.logicalTypes();
        this.descriptions = compiled.descriptions();
        this.originalOrder = compiled.originalOrder();
        this.edgeLabels = compiled.edgeLabels();

        // Pre-cache source nodes for update()
        TopologicalOrder topology = engine.topology();
        this.sourceNodes = new Node[topology.nodeCount()];
        for (int i = 0; i < topology.nodeCount(); i++) {
            if (topology.isSource(i)) {
                sourceNodes[i] = topology.node(i);
            }
        }

        // Register composite listener
        this.compositeListener = new CompositeStabilizationListener();
        this.engine.setListener(this.compositeListener);
    }

    // ── Listeners ────────────────────────────────────────────────

    /** Adds a listener to the composite (does not overwrite existing ones). */
    public void setListener(StabilizationListener listener) {
        compositeListener.addForComposite(listener);
    }

    // ── Domain Operations ────────────────────────────────────────

    /** Reads the current value of a Scalar node. */
    public double getDouble(String name) {
        Node node = nodes.get(name);
        if (node == null) {
            throw new IllegalArgumentException("Node not found: " + name);
        }
        if (node instanceof ScalarValue sv) {
            return sv.value();
        }
        throw new IllegalArgumentException("Node " + name + " is not a ScalarValue.");
    }

    /** Retrieves a node by name with type-safe casting. */
    public <T> T getNode(String name, Class<T> type) {
        Node node = nodes.get(name);
        if (node == null) {
            throw new IllegalArgumentException("Node not found: " + name);
        }
        if (!type.isInstance(node)) {
            throw new IllegalArgumentException(
                    "Node " + name + " is " + node.getClass().getSimpleName() + ", expected " + type.getSimpleName());
        }
        return type.cast(node);
    }

    /** Resolves a node name to its topological index. */
    public int getNodeId(String name) {
        return engine.topology().topoIndex(name);
    }

    /** Updates a Scalar source node by name without triggering stabilization. */
    public void update(String nodeName, double value) {
        update(getNodeId(nodeName), value);
    }

    /** Updates a Scalar source node by ID without triggering stabilization. */
    public void update(int nodeId, double value) {
        if (nodeId < 0 || nodeId >= sourceNodes.length)
            return;
        Node node = sourceNodes[nodeId];
        if (node instanceof ScalarSourceNode sn) {
            sn.update(value);
            engine.markDirty(nodeId);
        }
    }

    /** Updates a single element of a Vector source node by ID. */
    public void update(int nodeId, int index, double value) {
        if (nodeId < 0 || nodeId >= sourceNodes.length)
            return;
        Node node = sourceNodes[nodeId];
        if (node instanceof VectorSourceNode vsn) {
            vsn.updateAt(index, value);
            engine.markDirty(nodeId);
        }
    }

    /** Triggers a stabilization cycle. @return Number of nodes recomputed. */
    public int stabilize() {
        return engine.stabilize();
    }
}
