package com.trading.drg.web;

import com.trading.drg.api.Node;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.api.StabilizationListener;
import com.trading.drg.engine.TopologicalOrder;
import com.trading.drg.node.ScalarNode;
import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.util.LatencyTrackingListener;
import com.trading.drg.util.NodeProfileListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Comparator;

/**
 * A listener that observes the graph engine and broadcasts the
 * entire graph state (node values) along with optional telemetry
 * metrics via WebSockets at the end of every stabilization cycle.
 */
public class WebsocketPublisherListener implements StabilizationListener {
    private static final Logger log = LogManager.getLogger(WebsocketPublisherListener.class);

    private final StabilizationEngine engine;
    private final GraphDashboardServer server;

    // Optional metrics listeners
    private LatencyTrackingListener latencyListener;
    private NodeProfileListener profileListener;

    // We reuse a StringBuilder to minimize GC overhead when constructing the JSON
    // payload.
    // Note: Since StabilizationEngine is single-threaded, this is safe.
    private final StringBuilder jsonBuilder = new StringBuilder(1024);

    public WebsocketPublisherListener(StabilizationEngine engine, GraphDashboardServer server) {
        this.engine = engine;
        this.server = server;
    }

    public void setLatencyListener(LatencyTrackingListener latencyListener) {
        this.latencyListener = latencyListener;
    }

    public void setProfileListener(NodeProfileListener profileListener) {
        this.profileListener = profileListener;
    }

    @Override
    public void onStabilizationStart(long epoch) {
        // No action needed on start
    }

    @Override
    public void onStabilizationEnd(long epoch, int nodesStabilized) {
        // 1. Build a compact JSON payload representing the current state
        jsonBuilder.setLength(0);
        jsonBuilder.append("{\"epoch\":").append(epoch).append(",\"values\":{");

        TopologicalOrder topology = engine.topology();
        int nodeCount = topology.nodeCount();

        for (int i = 0; i < nodeCount; i++) {
            Node<?> node = topology.node(i);

            double val = 0.0;
            if (node instanceof ScalarNode sn) {
                val = sn.doubleValue();
            } else if (node instanceof ScalarSourceNode ssn) {
                val = ssn.doubleValue();
            }

            jsonBuilder.append("\"").append(node.name()).append("\":").append(val);

            if (i < nodeCount - 1) {
                jsonBuilder.append(",");
            }
        }

        jsonBuilder.append("}");

        // 2. Append metrics if available
        if (latencyListener != null || profileListener != null) {
            jsonBuilder.append(",\"metrics\":{");

            if (latencyListener != null) {
                jsonBuilder.append("\"latency\":{")
                        .append("\"avg\":").append(latencyListener.avgLatencyMicros()).append(",")
                        .append("\"min\":").append(latencyListener.minLatencyNanos() / 1000.0).append(",")
                        .append("\"max\":").append(latencyListener.maxLatencyNanos() / 1000.0)
                        .append("}");
            }

            if (latencyListener != null && profileListener != null) {
                jsonBuilder.append(",");
            }

            if (profileListener != null) {
                jsonBuilder.append("\"profile\":[");
                var topNodes = profileListener.getStats().entrySet().stream()
                        .sorted((e1, e2) -> Long.compare(e2.getValue().totalDurationNanos,
                                e1.getValue().totalDurationNanos))
                        .limit(5)
                        .toList();

                for (int i = 0; i < topNodes.size(); i++) {
                    var entry = topNodes.get(i);
                    jsonBuilder.append("{")
                            .append("\"name\":\"").append(entry.getKey()).append("\",")
                            .append("\"avg\":").append(entry.getValue().avgMicros())
                            .append("}");
                    if (i < topNodes.size() - 1) {
                        jsonBuilder.append(",");
                    }
                }
                jsonBuilder.append("]");
            }

            jsonBuilder.append("}");
        }

        jsonBuilder.append("}");

        // 3. Broadcast to all connected web clients
        server.broadcast(jsonBuilder.toString());
    }

    @Override
    public void onNodeStabilized(long epoch, int topoIndex, String nodeName, boolean changed, long durationNanos) {
        // We only care about the final state at the end of the epoch,
        // so we ignore individual node compute completions.
    }

    @Override
    public void onNodeError(long epoch, int topoIndex, String nodeName, Throwable error) {
        // In a real system, we might broadcast a special error payload here
        log.error("Dashboard Publisher observed Node Error in {}: {}", nodeName, error.getMessage());
    }
}
