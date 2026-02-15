package com.trading.drg.core;

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
    private final TopologicalOrder topology;

    // The "seen set" or "work list" equivalent.
    // dirty[i] == true means node i needs to be re-evaluated.
    private final boolean[] dirty;

    private int lastStabilizedCount;
    private long epoch;
    private StabilizationListener listener;

    public StabilizationEngine(TopologicalOrder topology) {
        this.topology = topology;
        this.dirty = new boolean[topology.nodeCount()];

        // Fix: Mark all source nodes as dirty initially so their values propagate
        // on the first stabilize() call.
        for (int i = 0; i < topology.nodeCount(); i++) {
            if (topology.isSource(i)) {
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
     * 1. Increment the epoch.
     * 2. Traverse the graph in topological order.
     * 3. Recompute dirty nodes.
     * 4. Propagate changes to children.
     * 5. Clear source node dirty flags.
     *
     * @return The number of nodes that were actually recomputed.
     */
    public int stabilize() {
        epoch++;
        int stabilizedCount = 0;
        final int n = topology.nodeCount();
        final StabilizationListener l = this.listener;
        final boolean hasListener = l != null;

        if (hasListener)
            l.onStabilizationStart(epoch);

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
            boolean changed = node.stabilize();
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

        // Cleanup: After stabilization, source nodes are no longer "newly updated".
        for (int ti = 0; ti < n; ti++) {
            if (topology.isSource(ti) && topology.node(ti) instanceof SourceNode<?> src)
                src.clearDirty();
        }

        this.lastStabilizedCount = stabilizedCount;
        if (hasListener)
            l.onStabilizationEnd(epoch, stabilizedCount);
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

    public TopologicalOrder topology() {
        return topology;
    }
}
