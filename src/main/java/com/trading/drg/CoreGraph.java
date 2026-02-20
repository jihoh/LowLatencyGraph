package com.trading.drg;

import com.trading.drg.api.Node;
import com.trading.drg.api.StabilizationListener;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.io.GraphDefinition;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.io.JsonParser;
// Removed Wiring imports
import com.trading.drg.api.ScalarValue;
import com.trading.drg.util.GraphExplain;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;

/**
 * A high-level wrapper around the DRG engine that simplifies JSON-based graph
 * instantiation
 * and event publishing.
 * <p>
 * This class handles:
 * <ul>
 * <li>Parsing JSON graph definitions</li>
 * <li>Compiling the graph using {@link JsonGraphCompiler} with built-in
 * factories</li>
 * <li>Setting up the {@link StabilizationEngine}</li>
 * <li>Configuring the LMAX Disruptor for single-threaded event processing</li>
 * <li>Providing simplified {@link #publish(String, double)} methods</li>
 * </ul>
 */
public class CoreGraph {
    private static final Logger log = LogManager.getLogger(CoreGraph.class);

    private final StabilizationEngine engine;
    private final Map<String, Node<?>> nodes;
    // Removed Disruptor, RingBuffer, Publisher

    private final com.trading.drg.util.CompositeStabilizationListener compositeListener;

    // Cache source nodes for O(1) updates
    private final Node<?>[] sourceNodes;

    /**
     * Creates a new CoreGraph from a JSON file path string.
     *
     * @param jsonPath relative or absolute path to the JSON graph definition.
     */
    public CoreGraph(String jsonPath) {
        this(Path.of(jsonPath));
    }

    /**
     * Creates a new CoreGraph from a JSON file path.
     *
     * @param jsonPath Path to the JSON graph definition.
     */
    public CoreGraph(Path jsonPath) {
        // 1. Parse & Compile
        GraphDefinition graphDef;
        try {
            graphDef = JsonParser.parseFile(jsonPath);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to load graph definition from " + jsonPath, e);
        }
        var compiler = new JsonGraphCompiler().registerBuiltIns();
        var compiled = compiler.compile(graphDef);

        this.engine = compiled.engine();
        this.nodes = compiled.nodesByName();

        // Pre-cache source nodes for update()
        var topology = engine.topology();
        this.sourceNodes = new Node<?>[topology.nodeCount()];
        for (int i = 0; i < topology.nodeCount(); i++) {
            if (topology.isSource(i)) {
                sourceNodes[i] = topology.node(i);
            }
        }

        // Register default listener via Composite
        this.compositeListener = new com.trading.drg.util.CompositeStabilizationListener();

        this.engine.setListener(this.compositeListener);

        // --- Export Graph Visualization ---
        try {
            String mermaid = new GraphExplain(engine).toMermaid();
            String fileName = jsonPath.getFileName().toString();
            String baseName = fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
            String mdName = baseName + ".md";
            java.nio.file.Files.writeString(java.nio.file.Path.of(mdName), mermaid);
            log.info("Graph visualization saved to {}", mdName);
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Registers a listener to monitor stabilization events.
     * Note: This adds to the composite listener rather than overwriting existing
     * listeners
     * (such as latency or profiling trackers).
     *
     * @param listener The listener to register.
     */
    public void setListener(StabilizationListener listener) {
        compositeListener.addForComposite(listener);
    }

    /**
     * Returns the underlying engine.
     *
     * @return The StabilizationEngine.
     */
    public StabilizationEngine getEngine() {
        return engine;
    }

    /**
     * Enables latency tracking.
     * If already enabled, returns the existing listener.
     */
    public com.trading.drg.util.LatencyTrackingListener enableLatencyTracking() {
        var latencyListener = new com.trading.drg.util.LatencyTrackingListener();
        compositeListener.addForComposite(latencyListener);
        return latencyListener;
    }

    /**
     * Enables detailed per-node profiling.
     * Use the returned listener to dump statistics.
     */
    public com.trading.drg.util.NodeProfileListener enableNodeProfiling() {
        var profileListener = new com.trading.drg.util.NodeProfileListener();
        compositeListener.addForComposite(profileListener);
        return profileListener;
    }

    /**
     * Helper to read the current value of a Scalar node directly from the graph.
     * Guaranteed safe in single-threaded usage.
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
     *
     * @param name The name of the node in the JSON definition.
     * @param <T>  The expected type of the node.
     * @return The node, or null if not found.
     */
    @SuppressWarnings("unchecked")
    public <T> T getNode(String name) {
        return (T) nodes.get(name);
    }

    /**
     * Helper to get nodeId by name.
     */
    public int getNodeId(String name) {
        return engine.topology().topoIndex(name);
    }

    /**
     * Directly updates a Scalar Input Node.
     * This method does NOT trigger stabilization. Call {@link #stabilize()} after a
     * batch of updates.
     *
     * @param nodeName The name of the source node.
     * @param value    The new value.
     */
    public void update(String nodeName, double value) {
        update(getNodeId(nodeName), value);
    }

    /**
     * Directly updates a Scalar Input Node by ID.
     * This method does NOT trigger stabilization. Call {@link #stabilize()} after a
     * batch of updates.
     *
     * @param nodeId The topological index of the source node.
     * @param value  The new value.
     */
    public void update(int nodeId, double value) {
        if (nodeId < 0 || nodeId >= sourceNodes.length)
            return;

        Node<?> node = sourceNodes[nodeId];
        if (node instanceof com.trading.drg.node.ScalarSourceNode sn) {
            sn.updateDouble(value);
            engine.markDirty(nodeId);
        }
    }

    public void update(int nodeId, int index, double value) {
        if (nodeId < 0 || nodeId >= sourceNodes.length)
            return;

        Node<?> node = sourceNodes[nodeId];
        if (node instanceof com.trading.drg.node.VectorSourceNode vsn) {
            vsn.updateAt(index, value);
            engine.markDirty(nodeId);
        }
    }

    /**
     * Triggers a stabilization cycle on the engine.
     *
     * @return Number of nodes recomputed.
     */
    public int stabilize() {
        return engine.stabilize();
    }

    // Deprecated / Backwards Compat proxies if needed,
    // but we are adhering to a "breaking change" refactor plan.
    // Methods start(), stop(), publish() are REMOVED.
}
