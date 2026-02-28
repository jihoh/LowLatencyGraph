package com.trading.drg.util;

import com.trading.drg.api.StabilizationListener;
import java.util.Arrays;

/** Aggregates performance statistics per node to identify bottlenecks. */
public class NodeProfileListener implements StabilizationListener {

    public static class NodeStats {
        public final String name;
        public long count;
        public long totalDurationNanos;
        public long minDurationNanos = Long.MAX_VALUE;
        public long maxDurationNanos = Long.MIN_VALUE;
        public long lastDurationNanos;

        public NodeStats(String name) {
            this.name = name;
        }

        void update(long duration) {
            count++;
            totalDurationNanos += duration;
            lastDurationNanos = duration;
            if (duration < minDurationNanos)
                minDurationNanos = duration;
            if (duration > maxDurationNanos)
                maxDurationNanos = duration;
        }

        public double avgMicros() {
            return count == 0 ? 0 : totalDurationNanos / (double) count / 1000.0;
        }
    }

    // Flat array mapping topoIndex -> NodeStats. Eliminates Map hashing overhead.
    private NodeStats[] statsArray = new NodeStats[0];

    /** @return Read-only snapshot of stats array for telemetry. */
    public NodeStats[] getStatsArray() {
        return statsArray;
    }

    @Override
    public void onStabilizationStart(long epoch) {
        // No-op
    }

    @Override
    public void onNodeStabilized(long epoch, int topoIndex, String nodeName, boolean changed, long durationNanos) {
        if (topoIndex >= statsArray.length) {
            // Lazy resize for initial graph boot or hot-reload expansion
            NodeStats[] newArr = new NodeStats[Math.max(topoIndex + 1, statsArray.length * 2)];
            System.arraycopy(statsArray, 0, newArr, 0, statsArray.length);
            statsArray = newArr;
        }
        if (statsArray[topoIndex] == null) {
            statsArray[topoIndex] = new NodeStats(nodeName);
        }
        statsArray[topoIndex].update(durationNanos);
    }

    @Override
    public void onNodeError(long epoch, int topoIndex, String nodeName, Throwable error) {
        // We could track error counts here if desired
    }

    @Override
    public void onStabilizationEnd(long epoch, int nodesStabilized) {
        // No-op
    }

    /** Resets all collected statistics. */
    public void reset() {
        for (int i = 0; i < statsArray.length; i++) {
            if (statsArray[i] != null) {
                statsArray[i].count = 0;
                statsArray[i].totalDurationNanos = 0;
                statsArray[i].lastDurationNanos = 0;
                statsArray[i].minDurationNanos = Long.MAX_VALUE;
                statsArray[i].maxDurationNanos = Long.MIN_VALUE;
            }
        }
    }

    /**
     * Returns a formatted table of node statistics.
     * Synchronized for safe reading from telemetry thread.
     */
    public synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-30s | %10s | %10s | %10s | %10s | %10s%n", "Node Name", "Count", "Recent(us)",
                "Avg (us)", "Min (us)",
                "Max (us)"));
        sb.append(
                "------------------------------------------------------------------------------------------------------\n");

        NodeStats[] validStats = Arrays.stream(statsArray)
                .filter(s -> s != null && s.count > 0)
                .toArray(NodeStats[]::new);

        Arrays.sort(validStats, (s1, s2) -> Long.compare(s2.totalDurationNanos, s1.totalDurationNanos));

        for (NodeStats s : validStats) {
            sb.append(String.format("%-30s | %10d | %10.2f | %10.2f | %10.2f | %10.2f%n",
                    truncate(s.name, 30),
                    s.count,
                    s.lastDurationNanos / 1000.0,
                    s.avgMicros(),
                    s.minDurationNanos / 1000.0,
                    s.maxDurationNanos / 1000.0));
        }

        return sb.toString();
    }

    private String truncate(String s, int len) {
        if (s.length() <= len)
            return s;
        return s.substring(0, len - 3) + "...";
    }
}
