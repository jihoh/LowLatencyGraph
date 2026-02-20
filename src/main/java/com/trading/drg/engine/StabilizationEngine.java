package com.trading.drg.engine;

import com.trading.drg.api.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private static final Logger log = LogManager.getLogger(StabilizationEngine.class);

    private final TopologicalOrder topology;

    // The "seen set" or "work list" equivalent.
    // dirty[i] == true means node i needs to be re-evaluated.
    private final boolean[] dirty;

    // Circuit Breaker state
    private boolean healthy = true;

    private int lastStabilizedCount;
    private long epoch;
    private StabilizationListener listener;

    public StabilizationEngine(TopologicalOrder topology) {
        this.topology = topology;
        this.dirty = new boolean[topology.nodeCount()];

        // Mark all source nodes as dirty initially so their values propagate
        // on the first stabilize() call.
        for (int i = 0; i < topology.nodeCount(); i++) {
            if (topology.isSource(i) && topology.node(i) instanceof SourceNode) {
                dirty[i] = true;
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
        dirty[topology.topoIndex(nodeName)] = true;
    }

    /**
     * Marks a node as dirty by topological index.
     * Extremely fast (array access).
     */
    public void markDirty(int topoIndex) {
        dirty[topoIndex] = true;
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
        if (!healthy) {
            throw new IllegalStateException(
                    "Graph is in unhealthy state due to previous errors. Manual reset required.");
        }

        epoch++;
        int stabilizedCount = 0;
        final int n = topology.nodeCount();
        final StabilizationListener l = this.listener;
        final boolean hasListener = l != null;

        if (hasListener)
            l.onStabilizationStart(epoch);

        // Track errors for this pass
        boolean passFailed = false;
        Throwable firstError = null;

        try {
            // Linear scan through topological order.
            // This is efficient because 'dirty' is visited sequentially (spatial locality).
            for (int ti = 0; ti < n; ti++) {
                if (!dirty[ti])
                    continue;

                // Clear dirty flag *before* processing.
                // Note: A node can be marked dirty again if it has a self-loop (illegal in DAG)
                // or if we were doing iterative solving (not supported here).
                dirty[ti] = false;

                Node<?> node = topology.node(ti);

                boolean changed = false;
                long nodeStart = 0;
                if (hasListener) {
                    nodeStart = System.nanoTime();
                }

                try {
                    changed = node.stabilize();
                } catch (Throwable e) {
                    passFailed = true;
                    if (firstError == null) {
                        firstError = e;
                    }
                    if (hasListener) {
                        l.onNodeError(epoch, ti, node.name(), e);
                    }
                    break; // Stop processing further nodes on critical failure
                }

                stabilizedCount++;

                if (hasListener) {
                    long duration = System.nanoTime() - nodeStart;
                    l.onNodeStabilized(epoch, ti, node.name(), changed, duration);

                    // Phase 8: NaN Detection
                    // If the node is a ScalarValue and evaluates to NaN, report it as an error
                    // even if no exception was thrown (e.g. handled by fail-safe).
                    if (node instanceof ScalarValue sv) {
                        if (Double.isNaN(sv.doubleValue())) {
                            l.onNodeError(epoch, ti, node.name(), new RuntimeException("Node evaluated to NaN"));
                        }
                    }
                }

                // If the value changed, we must visit all children.
                if (changed) {
                    // Use CSR structure to iterate children efficiently
                    final int start = topology.childrenStart(ti);
                    final int end = topology.childrenEnd(ti);
                    for (int ci = start; ci < end; ci++)
                        dirty[topology.childAt(ci)] = true;
                }
            }
        } finally {
            // Cleanup: After stabilization, source nodes are no longer "newly updated".
            this.lastStabilizedCount = stabilizedCount;
            if (hasListener)
                l.onStabilizationEnd(epoch, stabilizedCount);
        }

        if (passFailed) {
            this.healthy = false;
            throw new RuntimeException("Stabilization failed due to node errors. Graph is now unhealthy.", firstError);
        }

        return stabilizedCount;
    }

    public long epoch() {
        return epoch;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void resetHealth() {
        this.healthy = true;
    }

    public int lastStabilizedCount() {
        return lastStabilizedCount;
    }

    public int nodeCount() {
        return topology.nodeCount();
    }

    public TopologicalOrder topology() {
        return topology;
    }
}
