package com.trading.drg.web;

import com.trading.drg.CoreGraph;
import com.trading.drg.api.Node;
import com.trading.drg.api.ScalarValue;
import com.trading.drg.api.VectorValue;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.io.GraphDefinition;
import com.trading.drg.io.NodeType;
import com.trading.drg.node.BooleanNode;
import com.trading.drg.util.AllocationProfiler;
import com.trading.drg.util.LatencyTrackingListener;
import com.trading.drg.util.NodeProfileListener;
import com.trading.drg.util.SourceExtractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import lombok.Getter;

/**
 * Wires a {@link CoreGraph} to the live dashboard infrastructure.
 * Handles telemetry, web server, WebSocket broadcasting, and snapshot
 * generation.
 */
@Getter
public final class DashboardWiring {
    private final CoreGraph graph;

    private LatencyTrackingListener latencyListener;
    private NodeProfileListener profileListener;
    private DoubleSupplier backpressureSupplier;
    private AllocationProfiler allocationProfiler;
    private GraphDashboardServer dashboardServer;
    private long throttleIntervalMs = 0;

    public DashboardWiring(CoreGraph graph) {
        this.graph = graph;
    }

    /** Enables latency tracking. */
    public DashboardWiring enableLatencyTracking() {
        if (this.latencyListener == null) {
            this.latencyListener = new LatencyTrackingListener();
            graph.setListener(this.latencyListener);
        }
        return this;
    }

    /** Enables detailed per-node profiling. */
    public DashboardWiring enableNodeProfiling() {
        if (this.profileListener == null) {
            this.profileListener = new NodeProfileListener();
            graph.setListener(this.profileListener);
        }
        return this;
    }

    /**
     * Supplies dynamic backpressure load percentage (0.0 to 100.0) from a queuing
     * mechanism
     */
    public DashboardWiring withBackpressureSupplier(DoubleSupplier supplier) {
        this.backpressureSupplier = supplier;
        return this;
    }

    /**
     * Binds an AllocationProfiler to stream zero-GC confirmation bytes.
     */
    public DashboardWiring withAllocationProfiler(AllocationProfiler profiler) {
        this.allocationProfiler = profiler;
        return this;
    }

    /**
     * Configure a backend WebSocket publish interval.
     */
    public DashboardWiring withThrottleIntervalMs(long ms) {
        this.throttleIntervalMs = ms;
        return this;
    }

    /**
     * Binds Disruptor ring buffer telemetry for measuring backpressure percentage
     * automatically.
     */
    public DashboardWiring bindDisruptorTelemetry(com.lmax.disruptor.RingBuffer<?> ringBuffer) {
        return withBackpressureSupplier(() -> {
            long remaining = ringBuffer.remainingCapacity();
            long total = ringBuffer.getBufferSize();
            double used = (double) (total - remaining) / (double) total;
            return used * 100.0;
        });
    }

    /** Boots a Live Dashboard Server and wires it to the graph. */
    public DashboardWiring enableDashboardServer(int port) {
        if (this.dashboardServer == null) {
            this.dashboardServer = new GraphDashboardServer();
            this.dashboardServer.setSnapshotSupplier(this::buildSnapshotJson);

            // Lazily extract source codes when dashboard is needed
            Map<String, String> sourceCodes = buildSourceCodes();

            WebsocketPublisherListener wsListener = new WebsocketPublisherListener(
                    graph.getEngine(), this.dashboardServer, graph.getName(), graph.getVersion(),
                    graph.getLogicalTypes(), graph.getDescriptions(),
                    graph.getOriginalOrder(), graph.getEdgeLabels(),
                    sourceCodes);

            if (this.latencyListener != null) {
                wsListener.setLatencyListener(this.latencyListener);
            }
            if (this.profileListener != null) {
                wsListener.setProfileListener(this.profileListener);
            }
            if (this.backpressureSupplier != null) {
                wsListener.setBackpressureSupplier(this.backpressureSupplier);
            }
            if (this.allocationProfiler != null) {
                wsListener.setAllocationProfiler(this.allocationProfiler);
            }
            if (this.throttleIntervalMs > 0) {
                wsListener.setThrottleIntervalMs(this.throttleIntervalMs);
            }

            graph.setListener(wsListener);
            this.dashboardServer.start(port);
        }
        return this;
    }

    /** Lazily builds source code map from logicalTypes and NodeType classes. */
    private Map<String, String> buildSourceCodes() {
        Map<String, String> logicalTypes = graph.getLogicalTypes();
        HashMap<String, String> result = new HashMap<>(logicalTypes.size());
        for (Map.Entry<String, String> entry : logicalTypes.entrySet()) {
            try {
                NodeType type = NodeType.fromString(entry.getValue());
                if (type.getNodeClass() != null) {
                    result.put(entry.getKey(), SourceExtractor.extractClassSource(type.getNodeClass().getName()));
                }
            } catch (IllegalArgumentException e) {
                // Skip unknown types
                continue;
            }
        }
        return result;
    }

    /** Serializes the current graph state into a JSON snapshot. */
    public String buildSnapshotJson() {
        StabilizationEngine engine = graph.getEngine();
        Map<String, Node> nodes = graph.getNodes();

        GraphDefinition snapshot = new GraphDefinition();
        GraphDefinition.GraphInfo info = new GraphDefinition.GraphInfo();
        info.setName(graph.getName() + " (Snapshot)");
        info.setVersion(graph.getVersion());
        info.setEpoch(engine.epoch());
        snapshot.setGraph(info);

        List<GraphDefinition.NodeDef> expandedNodes = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        for (String nodeName : graph.getOriginalOrder()) {
            Node node = nodes.get(nodeName);
            if (node == null)
                continue;

            GraphDefinition.NodeDef def = new GraphDefinition.NodeDef();
            def.setName(nodeName);
            def.setType(graph.getLogicalTypes().get(nodeName));

            // Reconstruct Inputs map from Edge Labels
            Map<String, Map<String, String>> edgeLabels = graph.getEdgeLabels();
            if (edgeLabels.containsKey(nodeName)) {
                Map<String, String> original = edgeLabels.get(nodeName);
                Map<String, String> inverted = new HashMap<>();
                for (Map.Entry<String, String> entry : original.entrySet()) {
                    inverted.put(entry.getValue(), entry.getKey());
                }
                def.setInputs(inverted);
            }

            // Extract dynamic state and value
            Map<String, Object> props = new HashMap<>();

            if (node instanceof VectorValue vv) {
                Double[] safeArr = new Double[vv.size()];
                for (int i = 0; i < vv.size(); i++) {
                    double v = vv.valueAt(i);
                    safeArr[i] = Double.isNaN(v) ? null : v;
                }
                props.put("value", safeArr);
            } else if (node instanceof ScalarValue sv) {
                double d = sv.value();
                props.put("value", Double.isNaN(d) ? null : d);
            } else if (node instanceof BooleanNode bn) {
                props.put("value", bn.booleanValue());
            } else {
                props.put("value", null);
            }

            if (node instanceof VectorValue vv && vv.headers() != null) {
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
        } catch (JsonProcessingException e) {
            return "{\"error\":\"Failed to serialize snapshot: " + e.getMessage() + "\"}";
        }
    }
}
