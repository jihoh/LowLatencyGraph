package com.trading.drg.engine;

import com.trading.drg.api.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The core engine that drives the graph stabilization process.
 *
 * <p>
 * This engine is responsible for efficiently propagating updates from source
 * nodes
 * to all affected dependent nodes. It uses the {@link TopologicalOrder} to
 * visit nodes
 * in the correct order (parents before children).
 *
 * <h3>Algorithm</h3>
 * <ol>
 * <li><b>Mark Dirty:</b> When a source changes, it (or its dependents) are
 * marked "dirty" in a boolean array.</li>
 * <li><b>Iterate:</b> The engine iterates through the nodes in topological
 * order (0 to N-1).</li>
 * <li><b>Skip Clean:</b> If a node is not dirty, it is skipped entirely.</li>
 * <li><b>Recompute:</b> If dirty, {@link Node#stabilize()} is called.</li>
 * <li><b>Propagate:</b> If {@code stabilize()} returns {@code true} (meaning
 * the value changed),
 * all direct children of the node are marked dirty.</li>
 * </ol>
 *
 * <p>
 * This process guarantees that every node is recomputed at most once per
 * stabilization cycle,
 * and only if necessary.
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
     * <p>
     * This method will:
     * 1. Check if the engine is healthy (Circuit Breaker).
     * 2. Increment the epoch.
     * 3. Traverse the graph in topological order.
     * 4. Recompute dirty nodes.
     * 5. Propagate changes to children.
     * 6. Clear source node dirty flags.
     * 7. If errors occurred, mark unhealthy and throw (Fail Fast).
     *
     * @return The number of nodes that were actually recomputed.
     * @throws IllegalStateException if the engine is already unhealthy.
     * @throws RuntimeException      if the stabilization failed (wrapped cause).
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
                try {
                    changed = node.stabilize();
                } catch (Throwable e) {
                    // Circuit Breaker / Fail Fast logic
                    passFailed = true;
                    if (firstError == null)
                        firstError = e;

                    log.error("Failed to stabilize node {}: {}", node.name(), e.getMessage(), e);

                    if (hasListener) {
                        l.onNodeError(epoch, ti, node.name(), e);
                    }

                    // Continue to next node to isolate failure implies we try to stabilize others.
                    // But we MUST mark the engine as potentially unhealthy if this is critical.
                    // For now, we continue the loop to attempt partial update.
                    continue;
                }

                stabilizedCount++;

                if (hasListener)
                    l.onNodeStabilized(epoch, ti, node.name(), changed);

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
