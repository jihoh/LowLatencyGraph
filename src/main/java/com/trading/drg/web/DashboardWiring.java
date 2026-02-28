package com.trading.drg.web;

import com.trading.drg.CoreGraph;
import com.trading.drg.api.Node;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.io.GraphDefinition;
import com.trading.drg.util.LatencyTrackingListener;
import com.trading.drg.util.NodeProfileListener;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Wires a {@link CoreGraph} to the live dashboard infrastructure.
 * <p>
 * Handles: latency tracking, node profiling, dashboard server,
 * WebSocket publishing, and snapshot serialization.
 */
public final class DashboardWiring {
    private final CoreGraph graph;

    private LatencyTrackingListener latencyListener;
    private NodeProfileListener profileListener;
    private GraphDashboardServer dashboardServer;

    public DashboardWiring(CoreGraph graph) {
        this.graph = graph;
    }

    /**
     * Enables latency tracking.
     */
    public DashboardWiring enableLatencyTracking() {
        if (this.latencyListener == null) {
            this.latencyListener = new LatencyTrackingListener();
            graph.setListener(this.latencyListener);
        }
        return this;
    }

    /**
     * Enables detailed per-node profiling.
     */
    public DashboardWiring enableNodeProfiling() {
        if (this.profileListener == null) {
            this.profileListener = new NodeProfileListener();
            graph.setListener(this.profileListener);
        }
        return this;
    }

    /**
     * Boots a Live Dashboard Server and wires it to the graph.
     *
     * @param port the port to bind to (e.g., 8080)
     */
    public DashboardWiring enableDashboardServer(int port) {
        if (this.dashboardServer == null) {
            this.dashboardServer = new GraphDashboardServer();
            this.dashboardServer.setSnapshotSupplier(this::buildSnapshotJson);

            var wsListener = new WebsocketPublisherListener(
                    graph.getEngine(), this.dashboardServer, graph.getName(), graph.getVersion(),
                    graph.getLogicalTypes(), graph.getDescriptions(),
                    graph.getOriginalOrder(), graph.getEdgeLabels(),
                    graph.getSourceCodes());

            if (this.latencyListener != null) {
                wsListener.setLatencyListener(this.latencyListener);
            }
            if (this.profileListener != null) {
                wsListener.setProfileListener(this.profileListener);
            }

            graph.setListener(wsListener);
            this.dashboardServer.start(port);
        }
        return this;
    }

    public LatencyTrackingListener getLatencyListener() {
        return this.latencyListener;
    }

    public NodeProfileListener getProfileListener() {
        return this.profileListener;
    }

    public GraphDashboardServer getDashboardServer() {
        return this.dashboardServer;
    }

    /**
     * Serializes the current graph state into a JSON snapshot.
     */
    public String buildSnapshotJson() {
        StabilizationEngine engine = graph.getEngine();
        Map<String, Node<?>> nodes = graph.getNodes();

        GraphDefinition snapshot = new GraphDefinition();
        var info = new GraphDefinition.GraphInfo();
        info.setName(graph.getName() + " (Snapshot)");
        info.setVersion(graph.getVersion());
        info.setEpoch(engine.epoch());
        snapshot.setGraph(info);

        java.util.List<GraphDefinition.NodeDef> expandedNodes = new java.util.ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        for (String nodeName : graph.getOriginalOrder()) {
            Node<?> node = nodes.get(nodeName);
            if (node == null)
                continue;

            GraphDefinition.NodeDef def = new GraphDefinition.NodeDef();
            def.setName(nodeName);
            def.setType(graph.getLogicalTypes().get(nodeName));

            // Reconstruct Inputs map from Edge Labels
            Map<String, Map<String, String>> edgeLabels = graph.getEdgeLabels();
            if (edgeLabels.containsKey(nodeName)) {
                java.util.Map<String, String> original = edgeLabels.get(nodeName);
                java.util.Map<String, String> inverted = new java.util.HashMap<>();
                for (java.util.Map.Entry<String, String> entry : original.entrySet()) {
                    inverted.put(entry.getValue(), entry.getKey());
                }
                def.setInputs(inverted);
            }

            // Extract dynamic state and value
            Map<String, Object> props = new java.util.HashMap<>();
            if (node instanceof com.trading.drg.api.DynamicState ds) {
                StringBuilder stateSb = new StringBuilder("{");
                ds.serializeDynamicState(stateSb);
                stateSb.append("}");
                if (stateSb.length() > 2) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsedState = mapper.readValue(stateSb.toString(), Map.class);
                        props.putAll(parsedState);
                    } catch (Exception e) {
                        props.put("stateError", e.getMessage());
                    }
                }
            }

            Object val = node.value();
            if (val == null) {
                props.put("value", null);
            } else if (val instanceof double[] arr) {
                Double[] safeArr = new Double[arr.length];
                for (int i = 0; i < arr.length; i++)
                    safeArr[i] = Double.isNaN(arr[i]) ? null : arr[i];
                props.put("value", safeArr);
            } else if (val instanceof Double d) {
                props.put("value", d.isNaN() ? null : d);
            } else {
                props.put("value", val);
            }

            if (node instanceof com.trading.drg.api.VectorValue vv && vv.headers() != null) {
                props.put("headers", vv.headers());
            }

            if (!props.isEmpty()) {
                def.setProperties(props);
            }

            expandedNodes.add(def);
        }

        info.setNodes(expandedNodes);

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"error\":\"Failed to serialize snapshot: " + e.getMessage() + "\"}";
        }
    }
}
