package com.trading.drg.engine.telemetry;

import com.trading.drg.api.StabilizationListener;
import com.trading.drg.util.ErrorRateLimiter;
import org.HdrHistogram.Histogram;

/**
 * Tracks stabilization latency and exposes tail-latency percentiles
 * (P50 through P99.999) via two HdrHistogram modes:
 *
 * <ul>
 *   <li><b>Sliding Window</b> — overlapping double-phase histograms, staggered
 *       by half the window. Always reads from the mature phase, guaranteeing
 *       [window/2 .. window] of data with no sawtooth resets.</li>
 *   <li><b>All-Time</b> — records only after {@code warmupEpochs} to exclude
 *       JIT/class-loading noise from steady-state distribution.</li>
 * </ul>
 *
 * All recording is O(1) zero-allocation on the hot path.
 */
public final class LatencyTrackingListener implements StabilizationListener {
    private final ErrorRateLimiter errLimiter = new ErrorRateLimiter();
    private long stabilizeStartNanos, lastLatencyNanos;
    private long totalStabilizations, totalLatencyNanos;
    private long minLatencyNanos = Long.MAX_VALUE, maxLatencyNanos = Long.MIN_VALUE;
    private int lastNodesRecomputed;

    // ── Sliding Window (overlapping double-phase) ──
    private final Histogram phaseA = new Histogram(10_000_000_000L, 3);
    private final Histogram phaseB = new Histogram(10_000_000_000L, 3);
    private long phaseAStartNanos;
    private long phaseBStartNanos;
    private final long windowNanos;
    private final long rollingWindowSec;

    // ── All-Time (warmup-gated) ──
    private final Histogram allTimeHistogram = new Histogram(10_000_000_000L, 3);
    private final long warmupEpochs;

    private static final double[] PERCENTILE_POINTS = { 50.0, 75.0, 90.0, 99.0, 99.9, 99.99, 99.999 };

    public LatencyTrackingListener() {
        this(3600L, 0L);
    }

    public LatencyTrackingListener(long rollingWindowSec, long warmupEpochs) {
        this.rollingWindowSec = rollingWindowSec;
        this.warmupEpochs = warmupEpochs;
        this.windowNanos = rollingWindowSec * 1_000_000_000L;
        initPhaseStagger(System.nanoTime());
    }

    /** Staggers phaseB by half the window behind phaseA. */
    private void initPhaseStagger(long now) {
        this.phaseAStartNanos = now;
        this.phaseBStartNanos = now - (windowNanos / 2);
    }

    @Override
    public void onStabilizationStart(long epoch) {
        stabilizeStartNanos = System.nanoTime();
    }

    @Override
    public void onNodeStabilized(long epoch, int ti, String name, boolean changed, long durationNanos) {
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

        // Reset expired phases before recording so the current sample isn't lost
        long now = System.nanoTime();
        boolean aReset = (now - phaseAStartNanos >= windowNanos);
        boolean bReset = (now - phaseBStartNanos >= windowNanos);
        if (aReset) { phaseA.reset(); phaseAStartNanos = now; }
        if (bReset) { phaseB.reset(); phaseBStartNanos = now; }
        if (aReset && bReset) { phaseBStartNanos = now - (windowNanos / 2); }

        phaseA.recordValue(lastLatencyNanos);
        phaseB.recordValue(lastLatencyNanos);

        if (epoch >= warmupEpochs) {
            allTimeHistogram.recordValue(lastLatencyNanos);
        }
    }

    // ── Accessors ──

    public long lastLatencyNanos() { return lastLatencyNanos; }
    public double lastLatencyMicros() { return lastLatencyNanos / 1000.0; }
    public int lastNodesRecomputed() { return lastNodesRecomputed; }
    public long totalStabilizations() { return totalStabilizations; }
    public double avgLatencyNanos() { return totalStabilizations > 0 ? (double) totalLatencyNanos / totalStabilizations : 0; }
    public double avgLatencyMicros() { return avgLatencyNanos() / 1000.0; }
    public long minLatencyNanos() { return minLatencyNanos == Long.MAX_VALUE ? 0 : minLatencyNanos; }
    public long maxLatencyNanos() { return maxLatencyNanos == Long.MIN_VALUE ? 0 : maxLatencyNanos; }

    /**
     * Fills {@code dest} with sliding-window percentiles (μs).
     * Reads from the mature phase (the one accumulating longer).
     */
    public void fillRollingPercentilesMicros(double[] dest) {
        Histogram mature = (phaseAStartNanos <= phaseBStartNanos) ? phaseA : phaseB;
        for (int i = 0; i < PERCENTILE_POINTS.length; i++) {
            dest[i] = mature.getValueAtPercentile(PERCENTILE_POINTS[i]) / 1000.0;
        }
    }

    /** Fills {@code dest} with all-time (post-warmup) percentiles (μs). */
    public void fillAllTimePercentilesMicros(double[] dest) {
        for (int i = 0; i < PERCENTILE_POINTS.length; i++) {
            dest[i] = allTimeHistogram.getValueAtPercentile(PERCENTILE_POINTS[i]) / 1000.0;
        }
    }

    // Individual all-time accessors (retained for dump())
    public double p50LatencyMicros()    { return allTimeHistogram.getValueAtPercentile(50.0) / 1000.0; }
    public double p75LatencyMicros()    { return allTimeHistogram.getValueAtPercentile(75.0) / 1000.0; }
    public double p90LatencyMicros()    { return allTimeHistogram.getValueAtPercentile(90.0) / 1000.0; }
    public double p99LatencyMicros()    { return allTimeHistogram.getValueAtPercentile(99.0) / 1000.0; }
    public double p99_9LatencyMicros()  { return allTimeHistogram.getValueAtPercentile(99.9) / 1000.0; }
    public double p99_99LatencyMicros() { return allTimeHistogram.getValueAtPercentile(99.99) / 1000.0; }
    public double p99_999LatencyMicros(){ return allTimeHistogram.getValueAtPercentile(99.999) / 1000.0; }

    public void reset() {
        totalStabilizations = 0;
        totalLatencyNanos = 0;
        minLatencyNanos = Long.MAX_VALUE;
        maxLatencyNanos = Long.MIN_VALUE;
        phaseA.reset();
        phaseB.reset();
        initPhaseStagger(System.nanoTime());
        allTimeHistogram.reset();
    }

    public long getRollingWindowSec() { return rollingWindowSec; }
    public long getWarmupEpochs() { return warmupEpochs; }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-20s | %10s | %10s | %10s | %10s\n",
                "Metric", "Value", "Avg (us)", "Min (us)", "Max (us)"));
        sb.append("-".repeat(80)).append("\n");
        sb.append(String.format("%-20s | %10d | %10.2f | %10.2f | %10.2f\n",
                "Total Stabilizations",
                totalStabilizations, avgLatencyMicros(),
                minLatencyNanos() / 1000.0, maxLatencyNanos() / 1000.0));
        sb.append(String.format("P99: %.2f us, P99.99: %.2f us\n",
                p99LatencyMicros(), p99_99LatencyMicros()));
        return sb.toString();
    }
}
