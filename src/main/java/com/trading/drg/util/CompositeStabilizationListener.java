package com.trading.drg.util;

import com.trading.drg.api.StabilizationListener;
import java.util.Arrays;

/**
 * Aggregates multiple {@link StabilizationListener} instances with
 * zero-allocation iteration.
 */
public class CompositeStabilizationListener implements StabilizationListener {
    private StabilizationListener[] listeners = new StabilizationListener[0];

    public void addForComposite(StabilizationListener listener) {
        StabilizationListener[] old = listeners;
        StabilizationListener[] next = Arrays.copyOf(old, old.length + 1);
        next[old.length] = listener;
        listeners = next;
    }

    @Override
    public void onStabilizationStart(long epoch) {
        for (StabilizationListener l : listeners)
            l.onStabilizationStart(epoch);
    }

    @Override
    public void onNodeStabilized(long epoch, int topoIndex, String nodeName, boolean changed, long durationNanos) {
        for (StabilizationListener l : listeners)
            l.onNodeStabilized(epoch, topoIndex, nodeName, changed, durationNanos);
    }

    @Override
    public void onNodeError(long epoch, int topoIndex, String nodeName, Throwable error) {
        for (StabilizationListener l : listeners)
            l.onNodeError(epoch, topoIndex, nodeName, error);
    }

    @Override
    public void onStabilizationEnd(long epoch, int nodesStabilized) {
        for (StabilizationListener l : listeners)
            l.onStabilizationEnd(epoch, nodesStabilized);
    }
}
