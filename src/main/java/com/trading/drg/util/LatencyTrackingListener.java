package com.trading.drg.util;

import com.trading.drg.api.StabilizationListener;

/**
 * A listener that tracks performance metrics for graph stabilization.
 *
 * <p>
 * Captures:
 * <ul>
 * <li><b>Latency:</b> Min, Max, Average time per stabilization pass (in
 * nanoseconds).</li>
 * <li><b>Throughput:</b> Total number of stabilizations.</li>
 * <li><b>Workload:</b> Number of nodes recomputed per pass.</li>
 * </ul>
 *
 * <p>
 * This listener is designed to be low-overhead but should generally only be
 * enabled
 * for profiling or in non-production environments if nanosecond precision is
 * not critical.
 */
public final class LatencyTrackingListener implements StabilizationListener {
    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager
            .getLogger(LatencyTrackingListener.class);

    private final ErrorRateLimiter errLimiter = new ErrorRateLimiter(log, 1000); // 1-second throttle
    private long stabilizeStartNanos, lastLatencyNanos;
    private long totalStabilizations, totalLatencyNanos;
    private long minLatencyNanos = Long.MAX_VALUE, maxLatencyNanos = Long.MIN_VALUE;
    private int lastNodesRecomputed;

    @Override
    public void onStabilizationStart(long epoch) {
        stabilizeStartNanos = System.nanoTime();
    }

    @Override
    public void onNodeStabilized(long epoch, int ti, String name, boolean changed, long durationNanos) {
        // No-op for latency tracking to keep overhead minimal
    }

    @Override
    public void onNodeError(long epoch, int ti, String name, Throwable error) {
        errLimiter.log(String.format("Graph Failure at Node '%s': %s", name, error.getMessage()), null);
    }

    @Override
    public void onStabilizationEnd(long epoch, int n) {
        lastLatencyNanos = System.nanoTime() - stabilizeStartNanos;
        lastNodesRecomputed = n;
        totalStabilizations++;
        totalLatencyNanos += lastLatencyNanos;
        if (lastLatencyNanos < minLatencyNanos)
            minLatencyNanos = lastLatencyNanos;
        if (lastLatencyNanos > maxLatencyNanos)
            maxLatencyNanos = lastLatencyNanos;
    }

    public long lastLatencyNanos() {
        return lastLatencyNanos;
    }

    public double lastLatencyMicros() {
        return lastLatencyNanos / 1000.0;
    }

    public int lastNodesRecomputed() {
        return lastNodesRecomputed;
    }

    public long totalStabilizations() {
        return totalStabilizations;
    }

    public double avgLatencyNanos() {
        return totalStabilizations > 0 ? (double) totalLatencyNanos / totalStabilizations : 0;
    }

    public double avgLatencyMicros() {
        return avgLatencyNanos() / 1000.0;
    }

    public long minLatencyNanos() {
        return minLatencyNanos == Long.MAX_VALUE ? 0 : minLatencyNanos;
    }

    public long maxLatencyNanos() {
        return maxLatencyNanos == Long.MIN_VALUE ? 0 : maxLatencyNanos;
    }

    public void reset() {
        totalStabilizations = 0;
        totalLatencyNanos = 0;
        minLatencyNanos = Long.MAX_VALUE;
        maxLatencyNanos = Long.MIN_VALUE;
    }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-20s | %10s | %10s | %10s | %10s\n", "Metric", "Value", "Avg (us)", "Min (us)",
                "Max (us)"));
        sb.append("------------------------------------------------------------------------------------------\n");
        sb.append(String.format("%-20s | %10d | %10.2f | %10.2f | %10.2f\n",
                "Total Stabilizations",
                totalStabilizations,
                avgLatencyMicros(),
                minLatencyNanos() / 1000.0,
                maxLatencyNanos() / 1000.0));

        return sb.toString();
    }
}
