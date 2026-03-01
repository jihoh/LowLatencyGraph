package com.trading.drg.web;

import com.trading.drg.api.Node;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.api.StabilizationListener;
import com.trading.drg.engine.TopologicalOrder;
import com.trading.drg.util.LatencyTrackingListener;
import com.trading.drg.util.NodeProfileListener;

import lombok.Setter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Broadcasts graph state and telemetry via WebSockets after stabilization.
 * Utilizes a zero-allocation double-buffering scheme offloaded to a daemon
 * thread.
 * <p>
 * Delegates JVM metrics collection to {@link JvmMetricsCollector} and
 * JSON serialization to {@link JsonSnapshotSerializer}.
 */
public class WebsocketPublisherListener implements StabilizationListener {

    private final StabilizationEngine engine;
    private final GraphDashboardServer server;
    private final com.trading.drg.util.ErrorRateLimiter errLimiter = new com.trading.drg.util.ErrorRateLimiter();

    // Extracted collaborators
    private final JvmMetricsCollector jvmMetrics;
    private final JsonSnapshotSerializer serializer;

    // Pre-computed node metadata
    private final byte[] nodeKinds;
    private final int nodeCount;

    // Track NaNs natively via topoIndex
    private final long[] nanCounters;

    // Optional metrics listeners
    @Setter
    private LatencyTrackingListener latencyListener;
    @Setter
    private NodeProfileListener profileListener;

    @Setter
    private java.util.function.DoubleSupplier backpressureSupplier;

    @Setter
    private com.trading.drg.util.AllocationProfiler allocationProfiler;

    // ── Double-Buffered Snapshot (Zero allocation on hot path) ────
    private final double[][] bufScalars = new double[2][];
    private final double[][][] bufVectors = new double[2][][];
    private final String[][][] bufHeaders = new String[2][][];
    private final long[] bufEpoch = new long[2];
    private final int[] bufNodesStabilized = new int[2];
    private final int[] bufSourceCount = new int[2];
    private final long[] bufTotalEvents = new long[2];
    private final long[] bufEpochEvents = new long[2];
    private final double[] bufLastLatency = new double[2];
    private final double[] bufAvgLatency = new double[2];

    // Engine writes to buffers[writeSlot], then publishes via readySlot
    private final AtomicInteger readySlot = new AtomicInteger(-1);

    public WebsocketPublisherListener(StabilizationEngine engine, GraphDashboardServer server, String graphName,
            String graphVersion, java.util.Map<String, String> logicalTypes, java.util.Map<String, String> descriptions,
            java.util.List<String> originalOrder,
            java.util.Map<String, java.util.Map<String, String>> edgeLabels,
            java.util.Map<String, String> sourceCodes) {
        this.engine = engine;
        this.server = server;
        this.jvmMetrics = new JvmMetricsCollector();

        String initialMermaid = new com.trading.drg.util.GraphExplain(engine, logicalTypes, originalOrder)
                .toMermaid()
                .replace("\"", "\\\"")
                .replace("\n", "\\n");

        // Pre-compute JSON keys and node kinds
        TopologicalOrder topology = engine.topology();
        this.nodeCount = topology.nodeCount();
        String[] jsonKeys = new String[nodeCount];
        String[] jsonHeaderKeys = new String[nodeCount];
        this.nodeKinds = new byte[nodeCount];
        this.nanCounters = new long[nodeCount];

        for (int i = 0; i < nodeCount; i++) {
            String name = topology.node(i).name();
            jsonKeys[i] = "\"" + name + "\":";
            jsonHeaderKeys[i] = ",\"" + name + "_headers\":";
            if (topology.node(i) instanceof com.trading.drg.api.VectorValue) {
                nodeKinds[i] = JsonSnapshotSerializer.KIND_VECTOR;
            } else {
                nodeKinds[i] = JsonSnapshotSerializer.KIND_SCALAR;
            }
        }

        this.serializer = new JsonSnapshotSerializer(jsonKeys, jsonHeaderKeys, nodeKinds, nodeCount);

        // Pre-allocate double buffers
        for (int b = 0; b < 2; b++) {
            bufScalars[b] = new double[nodeCount];
            bufVectors[b] = new double[nodeCount][];
            bufHeaders[b] = new String[nodeCount][];
            for (int i = 0; i < nodeCount; i++) {
                if (nodeKinds[i] == JsonSnapshotSerializer.KIND_VECTOR) {
                    int sz = ((com.trading.drg.api.VectorValue) topology.node(i)).size();
                    bufVectors[b][i] = new double[sz];
                }
            }
        }

        // Start the dedicated I/O thread
        Thread ioThread = new Thread(this::ioLoop, "ws-publisher-io");
        ioThread.setDaemon(true);
        ioThread.start();

        // Construct and push the static config
        cacheInitialGraphConfig(graphName, graphVersion, initialMermaid, descriptions, edgeLabels, sourceCodes);
    }

    private void cacheInitialGraphConfig(String graphName, String graphVersion, String initialMermaid,
            java.util.Map<String, String> descriptions,
            java.util.Map<String, java.util.Map<String, String>> edgeLabels,
            java.util.Map<String, String> sourceCodes) {
        StringBuilder initBuilder = new StringBuilder(2048);
        TopologicalOrder topology = engine.topology();

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
                        .append(escapeJsonString(entry.getValue())).append("\"");
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
                            .append(escapeJsonString(childEntry.getValue())).append("\"");
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
                        .append(escapeJsonString(entry.getValue()))
                        .append("\"");
                firstSource = false;
            }
        }
        initBuilder.append("}");

        initBuilder.append("}");
        server.setInitialGraphConfig(initBuilder.toString());
    }

    private String escapeJsonString(String value) {
        if (value == null)
            return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public void onStabilizationStart(long epoch) {
        // No action needed
    }

    /** Hot path: Copies values to buffer and publishes atomically (Zero alloc). */
    @Override
    public void onStabilizationEnd(long epoch, int nodesStabilized) {
        // Pick the write slot: toggle between 0 and 1
        int ws = (readySlot.get() == 0) ? 1 : 0;

        TopologicalOrder topology = engine.topology();
        int srcCount = 0;

        double[] scalars = bufScalars[ws];
        double[][] vectors = bufVectors[ws];
        String[][] headers = bufHeaders[ws];

        for (int i = 0; i < nodeCount; i++) {
            if (topology.isSource(i))
                srcCount++;

            Node node = topology.node(i);
            if (nodeKinds[i] == JsonSnapshotSerializer.KIND_VECTOR) {
                var vv = (com.trading.drg.api.VectorValue) node;
                double[] dest = vectors[i];
                for (int v = 0; v < dest.length; v++) {
                    dest[v] = vv.valueAt(v);
                }
                headers[i] = vv.headers();
                scalars[i] = Double.NaN;
            } else {
                vectors[i] = null;
                headers[i] = null;
                if (node instanceof com.trading.drg.api.ScalarValue sv) {
                    scalars[i] = sv.value();
                } else if (node instanceof com.trading.drg.node.BooleanNode bn) {
                    scalars[i] = bn.booleanValue() ? 1.0 : 0.0;
                } else {
                    scalars[i] = Double.NaN;
                }
                if (Double.isNaN(scalars[i])) {
                    nanCounters[i]++;
                }
            }
        }

        // Write metadata into flat arrays (zero object alloc)
        bufEpoch[ws] = epoch;
        bufNodesStabilized[ws] = nodesStabilized;
        bufSourceCount[ws] = srcCount;
        bufTotalEvents[ws] = engine.totalEventsProcessed();
        bufEpochEvents[ws] = engine.lastEpochEvents();
        bufLastLatency[ws] = latencyListener != null ? latencyListener.lastLatencyMicros() : Double.NaN;
        bufAvgLatency[ws] = latencyListener != null ? latencyListener.avgLatencyMicros() : Double.NaN;

        // Publish: atomically make this slot visible to I/O thread
        readySlot.set(ws);
    }

    /** Dedicated I/O thread polling ready snapshots to build JSON and broadcast. */
    private void ioLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            int slot = readySlot.getAndSet(-1);
            if (slot < 0) {
                try {
                    Thread.sleep(1); // yield briefly, re-check
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            // Sample JVM telemetry on the I/O thread (never on hot path)
            jvmMetrics.collect();

            // Delegate JSON serialization
            String json = serializer.buildTickJson(
                    bufEpoch[slot], bufNodesStabilized[slot], bufSourceCount[slot],
                    bufTotalEvents[slot], bufEpochEvents[slot],
                    bufScalars[slot], bufVectors[slot], bufHeaders[slot],
                    jvmMetrics, allocationProfiler, backpressureSupplier,
                    bufLastLatency[slot], bufAvgLatency[slot],
                    latencyListener != null, profileListener != null,
                    profileListener, nanCounters);

            server.broadcast(json);
        }
    }

    @Override
    public void onNodeStabilized(long epoch, int topoIndex, String nodeName, boolean changed, long durationNanos) {
        // We only care about the final state at the end of the epoch
    }

    @Override
    public void onNodeError(long epoch, int topoIndex, String nodeName, Throwable error) {
        errLimiter.log(String.format("Dashboard Publisher observed Node Error in %s: %s", nodeName, error.getMessage()),
                null);
    }
}
