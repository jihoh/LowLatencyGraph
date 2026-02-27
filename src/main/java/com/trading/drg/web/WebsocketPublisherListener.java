package com.trading.drg.web;

import com.trading.drg.api.Node;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.api.StabilizationListener;
import com.trading.drg.engine.TopologicalOrder;
import com.trading.drg.util.LatencyTrackingListener;
import com.trading.drg.util.NodeProfileListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A listener that observes the graph engine and broadcasts the
 * entire graph state (node values) along with optional telemetry
 * metrics via WebSockets at the end of every stabilization cycle.
 */
public class WebsocketPublisherListener implements StabilizationListener {
    private static final Logger log = LogManager.getLogger(WebsocketPublisherListener.class);

    private final StabilizationEngine engine;
    private final GraphDashboardServer server;
    private final String graphName;
    private final String graphVersion;
    private final String initialMermaid;
    private final java.util.Map<String, String> logicalTypes;
    private final java.util.Map<String, String> descriptions;
    private final java.util.Map<String, String> sourceCodes;
    private final java.util.Map<String, java.util.Map<String, String>> edgeLabels;
    private final com.trading.drg.util.ErrorRateLimiter errLimiter = new com.trading.drg.util.ErrorRateLimiter(log,
            1000);

    // Track NaNs natively via topoIndex without Object boxing
    private long[] nanCounters = new long[0];

    // Pre-allocated buffer for in-place sorting
    private NodeProfileListener.NodeStats[] sortBuffer = new NodeProfileListener.NodeStats[0];

    // Optional metrics listeners
    private LatencyTrackingListener latencyListener;
    private NodeProfileListener profileListener;

    // We reuse a StringBuilder to minimize GC overhead when constructing the JSON
    // payload.
    // Note: Since StabilizationEngine is single-threaded, this is safe.
    private final StringBuilder jsonBuilder = new StringBuilder(1024);

    public WebsocketPublisherListener(StabilizationEngine engine, GraphDashboardServer server, String graphName,
            String graphVersion, java.util.Map<String, String> logicalTypes, java.util.Map<String, String> descriptions,
            java.util.List<String> originalOrder,
            java.util.Map<String, java.util.Map<String, String>> edgeLabels,
            java.util.Map<String, String> sourceCodes) {
        this.engine = engine;
        this.server = server;
        this.graphName = graphName;
        this.graphVersion = graphVersion;
        this.logicalTypes = logicalTypes;
        this.descriptions = descriptions;
        this.sourceCodes = sourceCodes;
        this.edgeLabels = edgeLabels;
        this.initialMermaid = new com.trading.drg.util.GraphExplain(engine, logicalTypes, originalOrder, edgeLabels)
                .toMermaid()
                .replace("\"", "\\\"")
                .replace("\n", "\\n");

        // Construct and push the Heavy Static Config directly into the WebServer cache
        // This completely eliminates generating and broadcasting JSON routing data
        // every 100ms tick.
        cacheInitialGraphConfig();
    }

    private void cacheInitialGraphConfig() {
        StringBuilder initBuilder = new StringBuilder(2048);
        TopologicalOrder topology = engine.topology();
        int nodeCount = topology.nodeCount();

        initBuilder.append("{\"type\":\"init\",")
                .append("\"graphName\":\"").append(graphName).append("\",")
                .append("\"graphVersion\":\"").append(graphVersion).append("\",")
                .append("\"topology\":\"").append(initialMermaid).append("\",")
                .append("\"routing\":{");

        for (int i = 0; i < nodeCount; i++) {
            initBuilder.append("\"").append(topology.node(i).name()).append("\":[");
            int childCount = topology.childCount(i);
            for (int j = 0; j < childCount; j++) {
                int childId = topology.child(i, j);
                initBuilder.append("\"").append(topology.node(childId).name()).append("\"");
                if (j < childCount - 1)
                    initBuilder.append(",");
            }
            initBuilder.append("]");
            if (i < nodeCount - 1)
                initBuilder.append(",");
        }
        initBuilder.append("},");

        initBuilder.append("\"descriptions\":{");
        boolean firstDesc = true;
        if (descriptions != null) {
            for (var entry : descriptions.entrySet()) {
                if (!firstDesc)
                    initBuilder.append(",");
                initBuilder.append("\"").append(entry.getKey()).append("\":\"")
                        .append(entry.getValue().replace("\"", "\\\"")).append("\"");
                firstDesc = false;
            }
        }
        initBuilder.append("},");

        initBuilder.append("\"edgeLabels\":{");
        boolean firstEdge = true;
        if (edgeLabels != null) {
            for (var entry : edgeLabels.entrySet()) {
                if (!firstEdge)
                    initBuilder.append(",");
                initBuilder.append("\"").append(entry.getKey()).append("\":{");
                boolean firstChild = true;
                for (var childEntry : entry.getValue().entrySet()) {
                    if (!firstChild)
                        initBuilder.append(",");
                    initBuilder.append("\"").append(childEntry.getKey()).append("\":\"")
                            .append(childEntry.getValue().replace("\"", "\\\"")).append("\"");
                    firstChild = false;
                }
                initBuilder.append("}");
                firstEdge = false;
            }
        }
        initBuilder.append("}");

        // Add Source Codes
        initBuilder.append(",\"sourceCodes\":{");
        if (sourceCodes != null) {
            boolean firstSource = true;
            for (var entry : sourceCodes.entrySet()) {
                if (!firstSource)
                    initBuilder.append(",");
                initBuilder.append("\"").append(entry.getKey()).append("\":\"")
                        .append(entry.getValue()
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t"))
                        .append("\"");
                firstSource = false;
            }
        }
        initBuilder.append("}");

        initBuilder.append("}");

        server.setInitialGraphConfig(initBuilder.toString());
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
        // 1. Build a compact JSON payload representing the high-frequency tick state
        jsonBuilder.setLength(0);
        jsonBuilder.append("{\"type\":\"tick\",")
                .append("\"epoch\":").append(epoch)
                .append(",\"values\":{");

        TopologicalOrder topology = engine.topology();
        int nodeCount = topology.nodeCount();
        int sourceCount = 0;

        // Ensure static array capacity dynamically expands if the graph loads hot
        if (nanCounters.length < nodeCount) {
            nanCounters = new long[nodeCount];
        }

        for (int i = 0; i < nodeCount; i++) {
            if (topology.isSource(i))
                sourceCount++;

            Node<?> node = topology.node(i);

            double scalarVal = Double.NaN;
            double[] vectorVal = null;
            String[] headers = null;

            if (node instanceof com.trading.drg.api.ScalarValue sv) {
                scalarVal = sv.doubleValue();
            } else if (node.value() instanceof Number num) {
                scalarVal = num.doubleValue();
            } else if (node instanceof com.trading.drg.api.VectorValue vv) {
                vectorVal = vv.value();
                headers = vv.headers();
            } else if (node.value() instanceof double[] arr) {
                vectorVal = arr;
            }

            if (vectorVal != null) {
                jsonBuilder.append("\"").append(node.name()).append("\":\"[");
                for (int v = 0; v < vectorVal.length; v++) {
                    double vVal = vectorVal[v];
                    jsonBuilder.append(Double.isNaN(vVal) ? "null" : vVal);
                    if (v < vectorVal.length - 1)
                        jsonBuilder.append(",");
                }
                jsonBuilder.append("]\"");

                if (headers != null) {
                    jsonBuilder.append(",\"").append(node.name()).append("_headers\":\"[");
                    for (int h = 0; h < headers.length; h++) {
                        jsonBuilder.append("\\\"").append(headers[h]).append("\\\"");
                        if (h < headers.length - 1)
                            jsonBuilder.append(",");
                    }
                    jsonBuilder.append("]\"");
                }
            } else if (Double.isNaN(scalarVal)) {
                nanCounters[i]++;
                jsonBuilder.append("\"").append(node.name()).append("\":\"NaN\"");
            } else {
                jsonBuilder.append("\"").append(node.name()).append("\":").append(scalarVal);
            }

            if (i < nodeCount - 1) {
                jsonBuilder.append(",");
            }
        }

        jsonBuilder.append("}");

        jsonBuilder.append(",\"states\":{");
        boolean firstState = true;
        for (int i = 0; i < nodeCount; i++) {
            Node<?> node = topology.node(i);
            if (node instanceof com.trading.drg.api.DynamicState ds) {
                int lenBefore = jsonBuilder.length();
                if (!firstState) {
                    jsonBuilder.append(",");
                }
                jsonBuilder.append("\"").append(node.name()).append("\":{");
                int lenBeforeState = jsonBuilder.length();

                ds.serializeDynamicState(jsonBuilder);

                if (jsonBuilder.length() == lenBeforeState) {
                    // Node didn't append any state, rollback key append
                    jsonBuilder.setLength(lenBefore);
                } else {
                    jsonBuilder.append("}");
                    firstState = false;
                }
            }
        }
        jsonBuilder.append("}");

        // 2. Append metrics
        jsonBuilder.append(",\"metrics\":{")
                .append("\"nodesUpdated\":").append(nodesStabilized).append(",")
                .append("\"totalNodes\":").append(nodeCount).append(",")
                .append("\"totalSourceNodes\":").append(sourceCount).append(",")
                .append("\"eventsProcessed\":").append(engine.totalEventsProcessed()).append(",")
                .append("\"epochEvents\":").append(engine.lastEpochEvents());

        if (latencyListener != null) {
            jsonBuilder.append(",\"latency\":{")
                    .append("\"latest\":").append(latencyListener.lastLatencyMicros()).append(",")
                    .append("\"avg\":").append(latencyListener.avgLatencyMicros())
                    .append("}");
        }

        if (profileListener != null) {
            jsonBuilder.append(",\"profile\":[");

            // Build snapshot from the native array without Streams or Boxing
            NodeProfileListener.NodeStats[] rawStats = profileListener.getStatsArray();
            int limit = Math.min(rawStats.length, nodeCount);

            if (sortBuffer.length < limit) {
                sortBuffer = new NodeProfileListener.NodeStats[limit * 2];
            }

            // Collect valid non-null stats up to node count
            int validCount = 0;
            for (int i = 0; i < limit; i++) {
                if (rawStats[i] != null && rawStats[i].count > 0) {
                    sortBuffer[validCount++] = rawStats[i];
                }
            }

            java.util.Arrays.sort(sortBuffer, 0, validCount,
                    (s1, s2) -> Long.compare(s2.totalDurationNanos, s1.totalDurationNanos));
            int topK = Math.min(validCount, 50);

            for (int i = 0; i < topK; i++) {
                NodeProfileListener.NodeStats s = sortBuffer[i];

                // Lookup index generically based on object equality to grab the NaN array
                // counter
                int localTopoId = -1;
                for (int t = 0; t < rawStats.length; t++) {
                    if (rawStats[t] == s) {
                        localTopoId = t;
                        break;
                    }
                }

                long nanCount = localTopoId != -1 && localTopoId < nanCounters.length ? nanCounters[localTopoId] : 0;

                jsonBuilder.append("{")
                        .append("\"name\":\"").append(s.name).append("\",")
                        .append("\"latest\":").append(s.lastDurationNanos / 1000.0).append(",")
                        .append("\"avg\":").append(s.avgMicros()).append(",")
                        .append("\"evaluations\":").append(s.count).append(",")
                        .append("\"nans\":").append(nanCount)
                        .append("}");

                if (i < topK - 1) {
                    jsonBuilder.append(",");
                }
            }
            jsonBuilder.append("]");

            // Clean up buffer references
            for (int i = 0; i < validCount; i++) {
                sortBuffer[i] = null;
            }
        }

        jsonBuilder.append("}"); // close metrics
        jsonBuilder.append("}"); // close tick object

        // 3. Broadcast to all connected web clients
        String payload = jsonBuilder.toString();
        server.broadcast(payload);
    }

    @Override
    public void onNodeStabilized(long epoch, int topoIndex, String nodeName, boolean changed, long durationNanos) {
        // We only care about the final state at the end of the epoch,
        // so we ignore individual node compute completions.
    }

    @Override
    public void onNodeError(long epoch, int topoIndex, String nodeName, Throwable error) {
        // In a real system, we might broadcast a special error payload here
        errLimiter.log(String.format("Dashboard Publisher observed Node Error in %s: %s", nodeName, error.getMessage()),
                null);
    }
}
