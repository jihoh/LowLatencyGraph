package com.trading.drg.util;

import com.trading.drg.api.StabilizationListener;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A listener that aggregates performance statistics per node.
 * Useful for identifying bottlenecks and tuning ScalarCutoffs.
 */
public class NodeProfileListener implements StabilizationListener {

    private static class NodeStats {
        long count;
        long totalDurationNanos;
        long minDurationNanos = Long.MAX_VALUE;
        long maxDurationNanos = Long.MIN_VALUE;

        void update(long duration) {
            count++;
            totalDurationNanos += duration;
            if (duration < minDurationNanos)
                minDurationNanos = duration;
            if (duration > maxDurationNanos)
                maxDurationNanos = duration;
        }

        double avgMicros() {
            return count == 0 ? 0 : (totalDurationNanos / (double) count) / 1000.0;
        }
    }

    // Map from Node Name -> Stats
    // Using HashMap for zero-overhead on the hot path (single-threaded).
    // Access in dump() is synchronized for safety if called from another thread.
    private final Map<String, NodeStats> stats = new HashMap<>();

    @Override
    public void onStabilizationStart(long epoch) {
        // No-op
    }

    @Override
    public void onNodeStabilized(long epoch, int topoIndex, String nodeName, boolean changed, long durationNanos) {
        stats.computeIfAbsent(nodeName, k -> new NodeStats()).update(durationNanos);
    }

    @Override
    public void onNodeError(long epoch, int topoIndex, String nodeName, Throwable error) {
        // We could track error counts here if desired
    }

    @Override
    public void onStabilizationEnd(long epoch, int nodesStabilized) {
        // No-op
    }

    /**
     * Resets all collected statistics.
     */
    public void reset() {
        stats.clear();
    }

    /**
     * Returns a formatted table of node statistics.
     * Synchronized to permit safe reading from an external monitoring thread
     * while the engine thread writes.
     */
    public synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-30s | %10s | %10s | %10s | %10s%n", "Node Name", "Count", "Avg (us)", "Min (us)",
                "Max (us)"));
        sb.append("------------------------------------------------------------------------------------------\n");

        stats.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().totalDurationNanos, e1.getValue().totalDurationNanos)) // Sort
                                                                                                                      // by
                                                                                                                      // total
                                                                                                                      // time
                                                                                                                      // desc
                .forEach(e -> {
                    String name = e.getKey();
                    NodeStats s = e.getValue();
                    sb.append(String.format("%-30s | %10d | %10.2f | %10.2f | %10.2f%n",
                            truncate(name, 30),
                            s.count,
                            s.avgMicros(),
                            s.minDurationNanos / 1000.0,
                            s.maxDurationNanos / 1000.0));
                });

        return sb.toString();
    }

    private String truncate(String s, int len) {
        if (s.length() <= len)
            return s;
        return s.substring(0, len - 3) + "...";
    }
}
