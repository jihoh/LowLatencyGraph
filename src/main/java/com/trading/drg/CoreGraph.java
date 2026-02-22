package com.trading.drg;

import com.trading.drg.api.Node;
import com.trading.drg.api.StabilizationListener;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.io.GraphDefinition;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.io.JsonParser;
// Removed Wiring imports
import com.trading.drg.api.ScalarValue;

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
    private final StabilizationEngine engine;
    private final Map<String, Node<?>> nodes;

    private final String name;
    private final String version;

    private final com.trading.drg.util.CompositeStabilizationListener compositeListener;

    // Optional trackers
    // Optional trackers
    private com.trading.drg.util.LatencyTrackingListener latencyListener;
    private com.trading.drg.util.NodeProfileListener profileListener;
    private com.trading.drg.web.GraphDashboardServer dashboardServer;

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
        var compiler = new JsonGraphCompiler();
        var compiled = compiler.compile(graphDef);

        var info = graphDef.getGraph();
        this.name = info != null ? info.getName() : "Unknown";
        this.version = info != null ? info.getVersion() : "1.0";

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

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    /**
     * Enables latency tracking.
     * If already enabled, it does nothing.
     */
    public CoreGraph enableLatencyTracking() {
        if (this.latencyListener == null) {
            this.latencyListener = new com.trading.drg.util.LatencyTrackingListener();
            compositeListener.addForComposite(this.latencyListener);
        }
        return this;
    }

    /**
     * Enables detailed per-node profiling.
     */
    public CoreGraph enableNodeProfiling() {
        if (this.profileListener == null) {
            this.profileListener = new com.trading.drg.util.NodeProfileListener();
            compositeListener.addForComposite(this.profileListener);
        }
        return this;
    }

    /**
     * Boots a Live Dashboard Server and wires it to the graph.
     *
     * @param port the port to bind to (e.g., 8080)
     */
    public CoreGraph enableDashboardServer(int port) {
        if (this.dashboardServer == null) {
            this.dashboardServer = new com.trading.drg.web.GraphDashboardServer();
            this.dashboardServer.start(port);

            var wsListener = new com.trading.drg.web.WebsocketPublisherListener(
                    this.engine, this.dashboardServer, this.name, this.version);

            if (this.latencyListener != null) {
                wsListener.setLatencyListener(this.latencyListener);
            }
            if (this.profileListener != null) {
                wsListener.setProfileListener(this.profileListener);
            }

            setListener(wsListener);
        }
        return this;
    }

    public com.trading.drg.util.LatencyTrackingListener getLatencyListener() {
        return this.latencyListener;
    }

    public com.trading.drg.util.NodeProfileListener getProfileListener() {
        return this.profileListener;
    }

    public com.trading.drg.web.GraphDashboardServer getDashboardServer() {
        return this.dashboardServer;
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
