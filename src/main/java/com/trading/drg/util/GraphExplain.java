package com.trading.drg.util;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;

import com.trading.drg.api.Node;
import com.trading.drg.api.StabilizationEngine;
import com.trading.drg.api.TopologicalOrder;
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

    /**
     * Generates a Mermaid JS graph diagram.
     * <p>
     * Renders nodes and edges in a format suitable for embedding in Markdown.
     * </p>
     */
    public String toMermaid() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("graph TD;\n");
        for (int i = 0; i < topology.nodeCount(); i++) {
            Node<?> node = topology.node(i);
            String safeName = sanitize(node.name());

            // Stylize nodes based on type/role
            if (topology.isSource(i)) {
                sb.append("  ").append(safeName).append("([\"").append(node.name()).append("\"]);\n");
                sb.append("  style ").append(safeName).append(" fill:#e1f5fe,stroke:#01579b,stroke-width:2px;\n");
            } else {
                sb.append("  ").append(safeName).append("[\"").append(node.name()).append("\"];\n");
            }

            int cc = topology.childCount(i);
            for (int j = 0; j < cc; j++) {
                Node<?> child = topology.node(topology.child(i, j));
                String safeChild = sanitize(child.name());
                sb.append("  ").append(safeName).append(" --> ").append(safeChild).append(";\n");
            }
        }
        return sb.toString();
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
