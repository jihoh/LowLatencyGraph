package com.trading.drg.util;

import com.trading.drg.api.Node;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.engine.TopologicalOrder;
import com.trading.drg.node.ScalarNode;
import com.trading.drg.node.ScalarSourceNode;

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
    private final java.util.Map<String, String> logicalTypes;
    private final java.util.List<String> displayOrder;
    private final java.util.Map<String, java.util.Map<String, String>> edgeLabels;

    public GraphExplain(StabilizationEngine engine) {
        this(engine, java.util.Collections.emptyMap(), java.util.Collections.emptyList(), null);
    }

    public GraphExplain(StabilizationEngine engine, java.util.Map<String, String> logicalTypes) {
        this(engine, logicalTypes, java.util.Collections.emptyList(), null);
    }

    public GraphExplain(StabilizationEngine engine, java.util.Map<String, String> logicalTypes,
            java.util.List<String> displayOrder) {
        this(engine, logicalTypes, displayOrder, null);
    }

    public GraphExplain(StabilizationEngine engine, java.util.Map<String, String> logicalTypes,
            java.util.List<String> displayOrder, java.util.Map<String, java.util.Map<String, String>> edgeLabels) {
        this.engine = engine;
        this.topology = engine.topology();
        this.logicalTypes = logicalTypes;
        this.displayOrder = displayOrder;
        this.edgeLabels = edgeLabels;
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
        if (node instanceof ScalarNode dn)
            sb.append("  Previous: ").append(dn.previousDoubleValue()).append('\n');
        else if (node instanceof ScalarSourceNode dsn)
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

        java.util.List<String> order = this.displayOrder != null && !this.displayOrder.isEmpty()
                ? this.displayOrder
                : new java.util.ArrayList<>();
        if (order.isEmpty()) {
            for (int i = 0; i < topology.nodeCount(); i++) {
                order.add(topology.node(i).name());
            }
        }

        // 1. Declare nodes in exact display order
        for (String nodeName : order) {
            int i = topology.topoIndex(nodeName);
            if (i < 0)
                continue;
            Node<?> node = topology.node(i);
            String safeName = sanitize(node.name());

            // Format the value nicely
            double val = 0.0;
            if (node instanceof ScalarNode sn) {
                val = sn.doubleValue();
            } else if (node instanceof ScalarSourceNode ssn) {
                val = ssn.doubleValue();
            }
            String valueStr = String.format("%.4f", val);
            String nodeType = logicalTypes.get(node.name());
            if (nodeType == null) {
                // Fallback for programmatic nodes without explicit JSON types
                String className = node.getClass().getSimpleName();
                if (className.endsWith("Node")) {
                    className = className.substring(0, className.length() - 4);
                }
                nodeType = className.replaceAll("([a-z])([A-Z]+)", "$1_$2").toUpperCase();
            }

            // Stylize nodes with semantic span classes for CSS isolation
            if (topology.isSource(i)) {
                sb.append("  ").append(safeName)
                        .append("[\"<div class='node-inner'><span class='node-title source-node'>").append(node.name())
                        .append("</span><span class='node-type'>").append(nodeType).append("</span>")
                        .append("<b class='node-value'>").append(valueStr).append("</b></div>\"];\n");
            } else {
                sb.append("  ").append(safeName).append("[\"<div class='node-inner'><span class='node-title'>")
                        .append(node.name())
                        .append("</span><span class='node-type'>").append(nodeType).append("</span>")
                        .append("<b class='node-value'>").append(valueStr).append("</b></div>\"];\n");
            }
        }

        // 2. Declare all edges afterwards
        for (String nodeName : order) {
            int i = topology.topoIndex(nodeName);
            if (i < 0)
                continue;
            Node<?> node = topology.node(i);
            String safeName = sanitize(node.name());

            int cc = topology.childCount(i);
            for (int j = 0; j < cc; j++) {
                Node<?> child = topology.node(topology.child(i, j));
                String safeChild = sanitize(child.name());

                String label = null;
                if (edgeLabels != null) {
                    var labelsForChild = edgeLabels.get(child.name());
                    if (labelsForChild != null) {
                        label = labelsForChild.get(node.name());
                    }
                }

                if (label != null) {
                    sb.append("  ").append(safeName).append(" -- \"").append(label).append("\" --> ").append(safeChild)
                            .append(";\n");
                } else {
                    sb.append("  ").append(safeName).append(" --> ").append(safeChild).append(";\n");
                }
            }
        }
        return sb.toString();
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
