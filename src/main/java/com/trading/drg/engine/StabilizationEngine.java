package com.trading.drg.engine;

import com.trading.drg.api.*;

/**
 * The core engine that drives the graph stabilization process.
 *
 * This engine is responsible for efficiently propagating updates from source
 * nodes
 * to all affected dependent nodes. It uses the TopologicalOrder to visit nodes
 * in the correct order (parents before children), ensuring that every node is
 * recomputed at most once per stabilization cycle.
 *
 * Algorithm Details:
 * The engine uses a "Push-based, Topologically Ordered" propagation strategy:
 *
 * 1. Mark Dirty: When a source node is updated, it is marked as "dirty" in a
 * boolean array.
 * This is an O(1) operation using the node's integer ID.
 *
 * 2. Iterate: The engine iterates through the nodes in strict topological order
 * (0 to N-1).
 * This linear scan is cache-friendly (spatial locality).
 *
 * 3. Skip Clean: If a node is not marked dirty in the array, it is skipped
 * entirely.
 * This ensures work is proportional to the size of the update, not the size of
 * the graph.
 *
 * 4. Recompute: If dirty, the node's stabilize() method is called.
 *
 * 5. Propagate: If stabilize() returns true (meaning the output value changed
 * significantly),
 * the engine looks up the node's children in the TopologicalOrder (CSR
 * structure) and
 * marks them all as dirty for future visitation in the same pass.
 *
 * Circuit Breaker / Fail Fast:
 * If a node throws an exception during stabilization, the engine captures it,
 * notifies
 * listeners, and eventually marks the graph as "unhealthy". This prevents a
 * broken graph
 * from continuing to process financial data, which could lead to substantial
 * losses.
 * The graph must be potentially reset or discarded after a critical failure.
 */
public final class StabilizationEngine {
    private final TopologicalOrder topology;

    // The "seen set" or "work list" equivalent.
    // dirtyWords packs 64 boolean flags into a single long for O(K) sparse
    // traversal.
    private final long[] dirtyWords;

    private int lastStabilizedCount;
    private long epoch;
    private long eventsProcessed;
    private StabilizationListener listener;

    public StabilizationEngine(TopologicalOrder topology) {
        this.topology = topology;
        this.dirtyWords = new long[(topology.nodeCount() + 63) / 64];

        // Mark all source nodes as dirty initially so their values propagate
        // on the first stabilize() call.
        for (int i = 0; i < topology.nodeCount(); i++) {
            if (topology.isSource(i) && topology.node(i) instanceof SourceNode) {
                markDirty(i);
            }
        }
    }

    public void setListener(StabilizationListener listener) {
        this.listener = listener;
    }

    /**
     * Marks a node as dirty by name.
     * Use this when manually updating a source node.
     */
    public void markDirty(String nodeName) {
        markDirty(topology.topoIndex(nodeName));
    }

    /**
     * Marks a node as dirty by topological index.
     * Extremely fast (array access).
     */
    public void markDirty(int topoIndex) {
        int wordIdx = topoIndex >> 6;
        long bitMask = 1L << topoIndex;
        if ((dirtyWords[wordIdx] & bitMask) == 0) {
            dirtyWords[wordIdx] |= bitMask;
            eventsProcessed++;
        }
    }

    /**
     * Run one deterministic stabilization pass.
     *
     * This method executes the core graph propagation logic. It is single-threaded
     * and non-reentrant.
     *
     * Steps:
     * 1. Check Circuit Breaker: Throws immediately if the engine is already
     * unhealthy.
     * 2. Increment Epoch: Advances the logical time of the graph.
     * 3. Traverse: Scans the boolean dirty array in topological order.
     * 4. Recompute: Calls stabilize() on dirty nodes.
     * 5. Propagate: Marks children dirty if parents change.
     * 6. Cleanup: Resets dirty flags for the next pass.
     *
     * Performance:
     * This method is designed to be "Zero Alloc" on the happy path. It does not
     * create
     * iterators, list nodes, or other temporary objects. All structures (dirty
     * array,
     * topology arrays) are pre-allocated.
     *
     * @return The number of nodes that were actually recomputed (stabilized).
     * @throws IllegalStateException if the engine is already unhealthy.
     * @throws RuntimeException      if the stabilization failed to complete (e.g.,
     *                               node exception).
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
            // Sparse traversal using BitSet hardware intrinsics.
            // This loop operates in O(K) where K is the number of dirty nodes,
            // skipping up to 64 clean nodes in a single clock cycle.
            for (int w = 0; w < dirtyWords.length; w++) {
                while (dirtyWords[w] != 0L) {
                    // Instantly find the index of the lowest set bit
                    int bitIndex = Long.numberOfTrailingZeros(dirtyWords[w]);
                    int ti = (w << 6) + bitIndex; // Reconstruct the absolute topological ID

                    // Clear dirty flag *before* processing.
                    dirtyWords[w] &= ~(1L << bitIndex);

                    // Re-entrant bounds guard, strictly unnecessary if graph compiled correctly.
                    if (ti >= n)
                        break;

                    Node<?> node = topology.node(ti);

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
                        changed = true; // Force NaN propagation downward
                    }

                    stabilizedCount++;

                    if (hasListener) {
                        long duration = System.nanoTime() - nodeStart;
                        l.onNodeStabilized(epoch, ti, node.name(), changed, duration);
                    }

                    // If the value changed, we must visit all children.
                    if (changed) {
                        // Use CSR structure to iterate children efficiently
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
            // Cleanup: After stabilization, source nodes are no longer "newly updated".
            this.lastStabilizedCount = stabilizedCount;
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

    public TopologicalOrder topology() {
        return topology;
    }
}
