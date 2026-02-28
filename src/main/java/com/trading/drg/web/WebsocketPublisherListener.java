package com.trading.drg.web;

import com.trading.drg.api.Node;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.api.StabilizationListener;
import com.trading.drg.engine.TopologicalOrder;
import com.trading.drg.util.LatencyTrackingListener;
import com.trading.drg.util.NodeProfileListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Broadcasts graph state and telemetry via WebSockets after stabilization.
 * Utilizes a zero-allocation double-buffering scheme offloaded to a daemon
 * thread.
 */
public class WebsocketPublisherListener implements StabilizationListener {
    private static final Logger log = LogManager.getLogger(WebsocketPublisherListener.class);

    private final StabilizationEngine engine;
    private final GraphDashboardServer server;
    private final String graphName;
    private final String graphVersion;
    private final MemoryMXBean memBean;
    private final ThreadMXBean threadBean;
    private final java.util.List<GarbageCollectorMXBean> gcBeans;
    private final java.util.List<MemoryPoolMXBean> poolBeans;
    private final RuntimeMXBean runtimeBean;
    private final String initialMermaid;
    private final java.util.Map<String, String> descriptions;
    private final java.util.Map<String, String> sourceCodes;
    private final java.util.Map<String, java.util.Map<String, String>> edgeLabels;
    private final com.trading.drg.util.ErrorRateLimiter errLimiter = new com.trading.drg.util.ErrorRateLimiter(log,
            1000);

    // Pre-computed JSON key prefixes indexed by topoIndex
    private final String[] jsonKeys;
    // Pre-computed header key prefixes
    private final String[] jsonHeaderKeys;
    // Node type flags (0=scalar, 1=vector)
    private final byte[] nodeKinds;
    private static final byte KIND_SCALAR = 0;
    private static final byte KIND_VECTOR = 1;

    // Track NaNs natively via topoIndex
    private final long[] nanCounters;

    // Optional metrics listeners
    @lombok.Setter
    private LatencyTrackingListener latencyListener;
    @lombok.Setter
    private NodeProfileListener profileListener;

    @lombok.Setter
    private java.util.function.DoubleSupplier backpressureSupplier;

    @lombok.Setter
    private com.trading.drg.util.AllocationProfiler allocationProfiler;

    // ── Double-Buffered Snapshot (Zero allocation on hot path) ────
    private final int nodeCount;
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
        this.graphName = graphName;
        this.graphVersion = graphVersion;
        this.descriptions = descriptions;
        this.sourceCodes = sourceCodes;
        this.edgeLabels = edgeLabels;
        this.memBean = ManagementFactory.getMemoryMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.poolBeans = ManagementFactory.getMemoryPoolMXBeans();
        this.runtimeBean = ManagementFactory.getRuntimeMXBean();
        this.initialMermaid = new com.trading.drg.util.GraphExplain(engine, logicalTypes, originalOrder)
                .toMermaid()
                .replace("\"", "\\\"")
                .replace("\n", "\\n");

        // Pre-compute JSON keys and node kinds
        TopologicalOrder topology = engine.topology();
        this.nodeCount = topology.nodeCount();
        this.jsonKeys = new String[nodeCount];
        this.jsonHeaderKeys = new String[nodeCount];
        this.nodeKinds = new byte[nodeCount];
        this.nanCounters = new long[nodeCount];

        for (int i = 0; i < nodeCount; i++) {
            String name = topology.node(i).name();
            jsonKeys[i] = "\"" + name + "\":";
            jsonHeaderKeys[i] = ",\"" + name + "_headers\":";
            if (topology.node(i) instanceof com.trading.drg.api.VectorValue) {
                nodeKinds[i] = KIND_VECTOR;
            } else {
                nodeKinds[i] = KIND_SCALAR;
            }
        }

        // Pre-allocate double buffers
        for (int b = 0; b < 2; b++) {
            bufScalars[b] = new double[nodeCount];
            bufVectors[b] = new double[nodeCount][];
            bufHeaders[b] = new String[nodeCount][];
            for (int i = 0; i < nodeCount; i++) {
                if (nodeKinds[i] == KIND_VECTOR) {
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
        cacheInitialGraphConfig();
    }

    private void cacheInitialGraphConfig() {
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
            if (nodeKinds[i] == KIND_VECTOR) {
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
        StringBuilder jsonBuilder = new StringBuilder(2048);

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

            // Read from the published slot
            double[] scalars = bufScalars[slot];
            double[][] vectors = bufVectors[slot];
            String[][] headers = bufHeaders[slot];
            long epoch = bufEpoch[slot];
            int nodesStabilized = bufNodesStabilized[slot];
            int srcCount = bufSourceCount[slot];
            long totalEvents = bufTotalEvents[slot];
            long epochEvents = bufEpochEvents[slot];
            double lastLat = bufLastLatency[slot];
            double avgLat = bufAvgLatency[slot];

            // Build JSON
            jsonBuilder.setLength(0);
            jsonBuilder.append("{\"type\":\"tick\",")
                    .append("\"epoch\":").append(epoch)
                    .append(",\"values\":{");

            for (int i = 0; i < nodeCount; i++) {
                if (i > 0)
                    jsonBuilder.append(",");
                jsonBuilder.append(jsonKeys[i]);

                if (vectors[i] != null) {
                    double[] vec = vectors[i];
                    jsonBuilder.append("[");
                    for (int v = 0; v < vec.length; v++) {
                        if (v > 0)
                            jsonBuilder.append(",");
                        if (!Double.isFinite(vec[v])) {
                            jsonBuilder.append("null");
                        } else {
                            jsonBuilder.append(vec[v]);
                        }
                    }
                    jsonBuilder.append("]");

                    if (headers[i] != null) {
                        jsonBuilder.append(jsonHeaderKeys[i]).append("[");
                        for (int h = 0; h < headers[i].length; h++) {
                            if (h > 0)
                                jsonBuilder.append(",");
                            jsonBuilder.append("\"").append(headers[i][h]).append("\"");
                        }
                        jsonBuilder.append("]");
                    }
                } else {
                    double val = scalars[i];
                    if (!Double.isFinite(val)) {
                        jsonBuilder.append("\"").append(val).append("\"");
                    } else {
                        jsonBuilder.append(val);
                    }
                }
            }

            // Metrics
            jsonBuilder.append("},\"metrics\":{")
                    .append("\"nodesUpdated\":").append(nodesStabilized).append(",")
                    .append("\"totalNodes\":").append(nodeCount).append(",")
                    .append("\"totalSourceNodes\":").append(srcCount).append(",")
                    .append("\"eventsProcessed\":").append(totalEvents).append(",")
                    .append("\"epochEvents\":").append(epochEvents);

            long heapUsed = memBean.getHeapMemoryUsage().getUsed();
            long heapMax = memBean.getHeapMemoryUsage().getMax();
            int threadCount = threadBean.getThreadCount();
            long uptimeMs = runtimeBean.getUptime();

            long youngGcCount = 0;
            long youngGcTime = 0;
            long oldGcCount = 0;
            long oldGcTime = 0;

            for (int i = 0; i < gcBeans.size(); i++) {
                GarbageCollectorMXBean gc = gcBeans.get(i);
                long c = gc.getCollectionCount();
                long t = gc.getCollectionTime();
                if (c > 0) {
                    String name = gc.getName();
                    if (name.contains("G1 Old") || name.contains("MarkSweep") || name.contains("PS Old")
                            || name.contains("ConcurrentMarkSweep")) {
                        oldGcCount += c;
                        oldGcTime += t;
                    } else {
                        youngGcCount += c;
                        youngGcTime += t;
                    }
                }
            }

            long edenUsed = 0, survivorUsed = 0, oldGenUsed = 0;
            long edenMax = 0, survivorMax = 0, oldGenMax = 0;
            for (int i = 0; i < poolBeans.size(); i++) {
                MemoryPoolMXBean p = poolBeans.get(i);
                MemoryUsage u = p.getUsage();
                if (u == null)
                    continue;
                String name = p.getName();
                if (name.contains("Eden")) {
                    edenUsed = u.getUsed();
                    edenMax = u.getMax();
                } else if (name.contains("Survivor")) {
                    survivorUsed = u.getUsed();
                    survivorMax = u.getMax();
                } else if (name.contains("Old") || name.contains("Tenured")) {
                    oldGenUsed = u.getUsed();
                    oldGenMax = u.getMax();
                }
            }

            jsonBuilder.append(",\"jvm\":{")
                    .append("\"heapUsed\":").append(heapUsed).append(",")
                    .append("\"heapMax\":").append(heapMax).append(",")
                    .append("\"edenUsed\":").append(edenUsed).append(",")
                    .append("\"edenMax\":").append(edenMax).append(",")
                    .append("\"survivorUsed\":").append(survivorUsed).append(",")
                    .append("\"survivorMax\":").append(survivorMax).append(",")
                    .append("\"oldGenUsed\":").append(oldGenUsed).append(",")
                    .append("\"oldGenMax\":").append(oldGenMax).append(",")
                    .append("\"uptime\":").append(uptimeMs).append(",")
                    .append("\"threads\":").append(threadCount).append(",")
                    .append("\"youngGcCount\":").append(youngGcCount).append(",")
                    .append("\"youngGcTime\":").append(youngGcTime).append(",")
                    .append("\"oldGcCount\":").append(oldGcCount).append(",")
                    .append("\"oldGcTime\":").append(oldGcTime);

            if (allocationProfiler != null) {
                jsonBuilder.append(",\"allocatedBytes\":").append(allocationProfiler.getLastAllocatedBytes());
            }
            jsonBuilder.append("}");

            if (backpressureSupplier != null) {
                jsonBuilder.append(",\"disruptor\":{\"backpressure\":");
                appendDouble(jsonBuilder, backpressureSupplier.getAsDouble());
                jsonBuilder.append("}");
            }

            if (latencyListener != null) {
                jsonBuilder.append(",\"latency\":{\"latest\":");
                appendDouble(jsonBuilder, lastLat);
                jsonBuilder.append(",\"avg\":");
                appendDouble(jsonBuilder, avgLat);
                jsonBuilder.append("}");
            }

            if (profileListener != null) {
                jsonBuilder.append(",\"profile\":[");
                NodeProfileListener.NodeStats[] rawStats = profileListener.getStatsArray();

                int limit = Math.min(50, rawStats.length);
                NodeProfileListener.NodeStats[] sortBuffer = new NodeProfileListener.NodeStats[rawStats.length];
                int validCount = 0;
                for (int i = 0; i < rawStats.length; i++) {
                    if (rawStats[i] != null && rawStats[i].count > 0) {
                        sortBuffer[validCount++] = rawStats[i];
                    }
                }

                java.util.Arrays.sort(sortBuffer, 0, validCount,
                        (s1, s2) -> Long.compare(s2.totalDurationNanos, s1.totalDurationNanos));

                limit = Math.min(limit, validCount);
                for (int i = 0; i < limit; i++) {
                    if (i > 0)
                        jsonBuilder.append(",");
                    NodeProfileListener.NodeStats s = sortBuffer[i];

                    int localTopoId = -1;
                    for (int t = 0; t < rawStats.length; t++) {
                        if (rawStats[t] == s) {
                            localTopoId = t;
                            break;
                        }
                    }
                    long nanCount = localTopoId != -1 && localTopoId < nanCounters.length ? nanCounters[localTopoId]
                            : 0;

                    jsonBuilder.append("{\"name\":\"").append(s.name).append("\",\"latest\":");
                    appendDouble(jsonBuilder, s.lastDurationNanos / 1000.0);
                    jsonBuilder.append(",\"avg\":");
                    appendDouble(jsonBuilder, s.avgMicros());
                    jsonBuilder.append(",\"evaluations\":").append(s.count)
                            .append(",\"nans\":").append(nanCount)
                            .append("}");
                }
                jsonBuilder.append("]");
            }
            jsonBuilder.append("}"); // close metrics
            jsonBuilder.append("}"); // close tick

            server.broadcast(jsonBuilder.toString());
        }
    }

    /** Appends a double to StringBuilder without autoboxing. */
    private static void appendDouble(StringBuilder sb, double val) {
        if (!Double.isFinite(val)) {
            sb.append("\"").append(val).append("\"");
        } else {
            sb.append(val);
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
