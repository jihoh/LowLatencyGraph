package com.trading.drg.node;

import com.trading.drg.api.ScalarValue;
import com.trading.drg.api.SourceNode;

/**
 * A source node that acts as a periodic event trigger in the graph.
 *
 * <p>When marked dirty via {@link com.trading.drg.engine.StabilizationEngine#markDirty(int)},
 * its {@link #stabilize()} method increments an internal tick count and returns {@code true},
 * propagating the dirty state to all downstream dependents.
 *
 * <p>Scheduling is managed by {@link com.trading.drg.CoreGraph#startTimers}, which uses a single
 * shared background thread to publish timer events into the event loop (e.g. LMAX Disruptor).
 */
public final class TimerSourceNode implements SourceNode, ScalarValue {
    private final String name;
    private final long intervalMs;
    private long ticks = 0;

    /**
     * Creates a timer source node.
     *
     * @param name       unique node name
     * @param intervalMs interval between ticks in milliseconds
     */
    public TimerSourceNode(String name, long intervalMs) {
        this.name = name;
        this.intervalMs = intervalMs;
    }

    @Override
    public String name() {
        return name;
    }

    /** Returns the configured interval in milliseconds. */
    public long intervalMs() {
        return intervalMs;
    }

    @Override
    public boolean stabilize() {
        ticks++;
        return true;
    }

    @Override
    public double value() {
        return (double) ticks;
    }
}
