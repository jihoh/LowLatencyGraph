package com.trading.drg.engine;

import com.trading.drg.api.Node;
import com.trading.drg.api.SourceNode;
import com.trading.drg.api.StabilizationListener;

/**
 * Drives the reactive graph stabilization process.
 * <p>
 * Propagates changes sequentially using a cache-friendly, push-based
 * topological traversal.
 */
public final class StabilizationEngine {
    private final TopologicalOrder topology;

    // Packed dirty flags for O(K) sparse traversal.
    private final long[] dirtyWords;

    private int lastStabilizedCount;
    private long epoch;
    private long eventsProcessed;
    private int eventsThisEpoch;
    private int lastEpochEvents;
    @lombok.Setter
    private StabilizationListener listener;

    public StabilizationEngine(TopologicalOrder topology) {
        this.topology = topology;
        this.dirtyWords = new long[(topology.nodeCount() + 63) / 64];

        // Mark source nodes dirty for initial propagation.
        for (int i = 0; i < topology.nodeCount(); i++) {
            if (topology.isSource(i) && topology.node(i) instanceof SourceNode) {
                markDirty(i);
            }
        }
    }

    /** Marks a node as dirty by name. */
    public void markDirty(String nodeName) {
        markDirty(topology.topoIndex(nodeName));
    }

    /** Marks a node as dirty by topological index. */
    public void markDirty(int topoIndex) {
        int wordIdx = topoIndex >> 6;
        long bitMask = 1L << topoIndex;
        if ((dirtyWords[wordIdx] & bitMask) == 0) {
            dirtyWords[wordIdx] |= bitMask;
            eventsProcessed++;
            eventsThisEpoch++;
        }
    }

    /**
     * Executes one stabilization pass to propagate dirty nodes.
     * 
     * @return Number of recomputed nodes.
     */
    public int stabilize() {
        epoch++;
        int stabilizedCount = 0;
        final int n = topology.nodeCount();
        final StabilizationListener l = this.listener;
        final boolean hasListener = l != null;

        if (hasListener)
            l.onStabilizationStart(epoch);

        try {
            // Sparse O(K) traversal skipping clean nodes.
            for (int w = 0; w < dirtyWords.length; w++) {
                while (dirtyWords[w] != 0L) {
                    int bitIndex = Long.numberOfTrailingZeros(dirtyWords[w]); // Find lowest set bit
                    int ti = (w << 6) + bitIndex; // Reconstruct topological ID

                    // Clear dirty flag before processing.
                    dirtyWords[w] &= ~(1L << bitIndex);

                    // Re-entrant bounds guard.
                    if (ti >= n)
                        break;

                    Node node = topology.node(ti);

                    boolean changed = false;
                    long nodeStart = 0;
                    if (hasListener) {
                        nodeStart = System.nanoTime();
                    }

                    try {
                        changed = node.stabilize();
                    } catch (Throwable e) {
                        if (hasListener) {
                            l.onNodeError(epoch, ti, node.name(), e);
                        }
                        changed = true; // Force NaN downward
                    }

                    stabilizedCount++;

                    if (hasListener) {
                        long duration = System.nanoTime() - nodeStart;
                        l.onNodeStabilized(epoch, ti, node.name(), changed, duration);
                    }

                    // Visit children if value changed.
                    if (changed) {
                        // Iterate children via CSR
                        final int start = topology.childrenStart(ti);
                        final int end = topology.childrenEnd(ti);
                        for (int ci = start; ci < end; ci++) {
                            int childTi = topology.childAt(ci);
                            dirtyWords[childTi >> 6] |= (1L << childTi);
                        }
                    }
                }
            }
        } finally {
            // Cleanup epoch state.
            this.lastStabilizedCount = stabilizedCount;
            this.lastEpochEvents = this.eventsThisEpoch;
            this.eventsThisEpoch = 0; // reset for next epoch batch

            if (hasListener)
                l.onStabilizationEnd(epoch, stabilizedCount);
        }

        return stabilizedCount;
    }

    public long epoch() {
        return epoch;
    }

    public int lastStabilizedCount() {
        return lastStabilizedCount;
    }

    public int nodeCount() {
        return topology.nodeCount();
    }

    public long totalEventsProcessed() {
        return eventsProcessed;
    }

    public int lastEpochEvents() {
        return lastEpochEvents;
    }

    public TopologicalOrder topology() {
        return topology;
    }
}
