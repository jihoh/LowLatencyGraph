package com.trading.drg.web;

import com.trading.drg.util.NodeProfileListener;

/**
 * Zero-allocation JSON serializer for streaming graph tick data over WebSocket.
 * Extracted from WebsocketPublisherListener to isolate serialization logic.
 */
public final class JsonSnapshotSerializer {

    private final StringBuilder sb;
    private final String[] jsonKeys;
    private final String[] jsonHeaderKeys;
    private final int nodeCount;

    static final byte KIND_SCALAR = 0;
    static final byte KIND_VECTOR = 1;

    public JsonSnapshotSerializer(String[] jsonKeys, String[] jsonHeaderKeys, byte[] nodeKinds, int nodeCount) {
        this.sb = new StringBuilder(4096);
        this.jsonKeys = jsonKeys;
        this.jsonHeaderKeys = jsonHeaderKeys;
        this.nodeCount = nodeCount;
    }

    /**
     * Builds the full tick JSON payload using pre-captured buffer data.
     *
     * @return the JSON string ready for WebSocket broadcast
     */
    public String buildTickJson(
            long epoch, int nodesStabilized, int srcCount, long totalEvents, long epochEvents,
            double[] scalars, double[][] vectors, String[][] headers,
            JvmMetricsCollector jvmMetrics,
            com.trading.drg.util.AllocationProfiler allocationProfiler,
            java.util.function.DoubleSupplier backpressureSupplier,
            double lastLatency, double avgLatency,
            boolean hasLatency, boolean hasProfile,
            NodeProfileListener profileListener,
            long[] nanCounters) {

        sb.setLength(0);
        sb.append("{\"type\":\"tick\",")
                .append("\"epoch\":").append(epoch)
                .append(",\"values\":{");

        // ── Node values ──
        appendNodeValues(scalars, vectors, headers);

        // ── Engine metrics ──
        sb.append("},\"metrics\":{")
                .append("\"nodesUpdated\":").append(nodesStabilized).append(",")
                .append("\"totalNodes\":").append(nodeCount).append(",")
                .append("\"totalSourceNodes\":").append(srcCount).append(",")
                .append("\"eventsProcessed\":").append(totalEvents).append(",")
                .append("\"epochEvents\":").append(epochEvents);

        // ── JVM metrics ──
        sb.append(",\"jvm\":");
        jvmMetrics.appendJson(sb);
        if (allocationProfiler != null) {
            sb.append(",\"allocatedBytes\":").append(allocationProfiler.getLastAllocatedBytes());
        }
        sb.append("}");

        // ── Disruptor backpressure ──
        if (backpressureSupplier != null) {
            sb.append(",\"disruptor\":{\"backpressure\":");
            appendDouble(sb, backpressureSupplier.getAsDouble());
            sb.append("}");
        }

        // ── Latency ──
        if (hasLatency) {
            sb.append(",\"latency\":{\"latest\":");
            appendDouble(sb, lastLatency);
            sb.append(",\"avg\":");
            appendDouble(sb, avgLatency);
            sb.append("}");
        }

        // ── Per-node profile ──
        if (hasProfile && profileListener != null) {
            appendProfileStats(profileListener, nanCounters);
        }

        sb.append("}"); // close metrics
        sb.append("}"); // close tick

        return sb.toString();
    }

    private void appendNodeValues(double[] scalars, double[][] vectors, String[][] headers) {
        for (int i = 0; i < nodeCount; i++) {
            if (i > 0)
                sb.append(",");
            sb.append(jsonKeys[i]);

            if (vectors[i] != null) {
                double[] vec = vectors[i];
                sb.append("[");
                for (int v = 0; v < vec.length; v++) {
                    if (v > 0)
                        sb.append(",");
                    if (!Double.isFinite(vec[v])) {
                        sb.append("null");
                    } else {
                        sb.append(vec[v]);
                    }
                }
                sb.append("]");

                if (headers[i] != null) {
                    sb.append(jsonHeaderKeys[i]).append("[");
                    for (int h = 0; h < headers[i].length; h++) {
                        if (h > 0)
                            sb.append(",");
                        sb.append("\"").append(headers[i][h]).append("\"");
                    }
                    sb.append("]");
                }
            } else {
                double val = scalars[i];
                if (!Double.isFinite(val)) {
                    sb.append("\"").append(val).append("\"");
                } else {
                    sb.append(val);
                }
            }
        }
    }

    private void appendProfileStats(NodeProfileListener profileListener, long[] nanCounters) {
        sb.append(",\"profile\":[");
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
                sb.append(",");
            NodeProfileListener.NodeStats s = sortBuffer[i];

            int localTopoId = -1;
            for (int t = 0; t < rawStats.length; t++) {
                if (rawStats[t] == s) {
                    localTopoId = t;
                    break;
                }
            }
            long nanCount = localTopoId != -1 && localTopoId < nanCounters.length ? nanCounters[localTopoId] : 0;

            sb.append("{\"name\":\"").append(s.name).append("\",\"latest\":");
            appendDouble(sb, s.lastDurationNanos / 1000.0);
            sb.append(",\"avg\":");
            appendDouble(sb, s.avgMicros());
            sb.append(",\"evaluations\":").append(s.count)
                    .append(",\"nans\":").append(nanCount)
                    .append("}");
        }
        sb.append("]");
    }

    /** Appends a double to StringBuilder without autoboxing. */
    static void appendDouble(StringBuilder sb, double val) {
        if (!Double.isFinite(val)) {
            sb.append("\"").append(val).append("\"");
        } else {
            sb.append(val);
        }
    }
}
