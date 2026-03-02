package com.trading.drg.web;

import com.trading.drg.util.AllocationProfiler;
import com.trading.drg.util.NodeProfileListener;

import java.util.Arrays;

/**
 * Zero-allocation JSON serializer for streaming graph tick data over WebSocket.
 * Extracted from WebsocketPublisherListener to isolate serialization logic.
 */
public final class JsonSnapshotSerializer {

    private final StringBuilder sb;
    private final String[] jsonKeys;
    private final String[] jsonHeaderKeys;
    private final int nodeCount;
    private final int edgeCount;

    static final byte KIND_SCALAR = 0;
    static final byte KIND_VECTOR = 1;

    public JsonSnapshotSerializer(String[] jsonKeys, String[] jsonHeaderKeys, byte[] nodeKinds, int nodeCount,
            int edgeCount) {
        this.sb = new StringBuilder(4096);
        this.jsonKeys = jsonKeys;
        this.jsonHeaderKeys = jsonHeaderKeys;
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
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
            AllocationProfiler allocationProfiler,
            double maxBackpressure,
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
                .append("\"totalEdges\":").append(edgeCount).append(",")
                .append("\"totalSourceNodes\":").append(srcCount).append(",")
                .append("\"eventsProcessed\":").append(totalEvents).append(",")
                .append("\"epochEvents\":").append(epochEvents);

        // ── JVM metrics ──
        sb.append(",\"jvm\":");
        jvmMetrics.appendJson(sb);
        if (allocationProfiler != null) {
            sb.append(",\"allocatedBytes\":").append(allocationProfiler.getLastAllocatedBytes());
            sb.append(",\"cumulativeAllocatedBytes\":").append(allocationProfiler.getCumulativeAllocatedBytes());
        }
        sb.append("}");

        // ── Disruptor backpressure ──
        if (maxBackpressure >= 0) {
            sb.append(",\"disruptor\":{\"backpressure\":");
            appendDouble(sb, maxBackpressure);
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

    private static class LocalStat {
        final String name;
        final long totalDurationNanos;
        final long lastDurationNanos;
        final double avgMicros;
        final long count;
        final long nanCount;

        LocalStat(NodeProfileListener.NodeStats s, int topoId, long[] nanCounters) {
            this.name = s.name;
            this.totalDurationNanos = s.totalDurationNanos;
            this.lastDurationNanos = s.lastDurationNanos;
            this.avgMicros = s.avgMicros();
            this.count = s.count;
            this.nanCount = topoId != -1 && topoId < nanCounters.length ? nanCounters[topoId] : 0;
        }
    }

    private void appendProfileStats(NodeProfileListener profileListener, long[] nanCounters) {
        sb.append(",\"profile\":[");
        NodeProfileListener.NodeStats[] rawStats = profileListener.getStatsArray();

        int limit = Math.min(50, rawStats.length);
        LocalStat[] sortBuffer = new LocalStat[rawStats.length];
        int validCount = 0;
        for (int i = 0; i < rawStats.length; i++) {
            NodeProfileListener.NodeStats s = rawStats[i];
            if (s != null && s.count > 0) {
                sortBuffer[validCount++] = new LocalStat(s, i, nanCounters);
            }
        }

        Arrays.sort(sortBuffer, 0, validCount,
                (s1, s2) -> Long.compare(s2.totalDurationNanos, s1.totalDurationNanos));

        limit = Math.min(limit, validCount);
        for (int i = 0; i < limit; i++) {
            if (i > 0)
                sb.append(",");
            LocalStat s = sortBuffer[i];

            sb.append("{\"name\":\"").append(s.name).append("\",\"latest\":");
            appendDouble(sb, s.lastDurationNanos / 1000.0);
            sb.append(",\"avg\":");
            appendDouble(sb, s.avgMicros);
            sb.append(",\"evaluations\":").append(s.count)
                    .append(",\"nans\":").append(s.nanCount)
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
