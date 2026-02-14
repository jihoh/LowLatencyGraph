package com.trading.drg.util;

import com.trading.drg.core.Node;
import com.trading.drg.core.StabilizationEngine;
import com.trading.drg.core.TopologicalOrder;
import com.trading.drg.node.DoubleNode;
import com.trading.drg.node.DoubleSourceNode;

/**
 * Diagnostic utility for inspecting graph state and topology.
 *
 * <p>
 * This class generates human-readable string representations of the graph
 * structure
 * and the current state of specific nodes.
 *
 * <p>
 * <b>Usage:</b> Intended for debugging sessions, logging errors, or
 * "toString()" style diagnostics.
 * Do <b>not</b> use on the hot path (allocates strings, iterates collections).
 */
public final class GraphExplain {
    private final StabilizationEngine engine;
    private final TopologicalOrder topology;

    public GraphExplain(StabilizationEngine engine) {
        this.engine = engine;
        this.topology = engine.topology();
    }

    /**
     * Dumps detailed state of a single node.
     */
    public String explainNode(String nodeName) {
        int idx = topology.topoIndex(nodeName);
        Node<?> node = topology.node(idx);
        StringBuilder sb = new StringBuilder(256);
        sb.append("Node: ").append(nodeName).append('\n')
                .append("  Topo index: ").append(idx).append('\n')
                .append("  Type: ").append(node.getClass().getSimpleName()).append('\n')
                .append("  Is source: ").append(topology.isSource(idx)).append('\n')
                .append("  Current value: ").append(node.value()).append('\n');
        if (node instanceof DoubleNode dn)
            sb.append("  Previous: ").append(dn.previousDoubleValue()).append('\n');
        else if (node instanceof DoubleSourceNode dsn)
            sb.append("  Previous: ").append(dsn.previousDoubleValue()).append('\n');
        int cc = topology.childCount(idx);
        sb.append("  Children (").append(cc).append("): ");
        for (int i = 0; i < cc; i++) {
            sb.append(topology.node(topology.child(idx, i)).name());
            if (i < cc - 1)
                sb.append(", ");
        }
        return sb.append('\n').toString();
    }

    /**
     * returns summary of the last stabilization pass.
     */
    public String explainLastStabilization() {
        return "Epoch: " + engine.epoch() + ", Recomputed: " + engine.lastStabilizedCount() + "/" + engine.nodeCount();
    }

    /**
     * Dumps the entire topology in dot-like text format.
     */
    public String dumpTopology() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Graph (").append(topology.nodeCount()).append(" nodes):\n");
        for (int i = 0; i < topology.nodeCount(); i++) {
            Node<?> node = topology.node(i);
            sb.append("  [").append(i).append("] ").append(node.name());
            if (topology.isSource(i))
                sb.append(" (SRC)");
            int cc = topology.childCount(i);
            if (cc > 0) {
                sb.append(" → ");
                for (int j = 0; j < cc; j++) {
                    sb.append(topology.node(topology.child(i, j)).name());
                    if (j < cc - 1)
                        sb.append(", ");
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
