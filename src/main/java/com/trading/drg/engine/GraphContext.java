package com.trading.drg.engine;

import com.trading.drg.api.*;

import java.util.Map;

/**
 * A container holding the "live" state of a built graph.
 *
 * The GraphContext is the primary runtime object returned by the GraphBuilder.
 * It bridges the gap between the high-performance, index-based internal engine
 * and the user-facing application code.
 *
 * Responsibilities:
 * 1. Engine Access: Holds the reference to the StabilizationEngine, which
 * allows
 * the application to drive the graph (e.g., call stabilize()).
 * 2. Name Resolution: Maintains a map of "String Name" -> "Node Object". This
 * allows users to look up nodes by name for UI binding, reporting, or
 * debugging.
 * 3. ID Resolution: Provides fast lookups for converting a Node Name to its
 * integer Topological ID, which is required for high-frequency source updates.
 *
 * Thread Safety:
 * The GraphContext itself is immutable after construction. However, the Node
 * objects
 * it helps you retrieve are mutable state containers managed by the Engine.
 * Access
 * to Node values must be coordinated with the Engine's stabilization cycle.
 */
public final class GraphContext {
    private final String name;
    private final StabilizationEngine engine;
    private final Map<String, Node<?>> nodesByName;

    public GraphContext(String name, StabilizationEngine engine, Map<String, Node<?>> nodesByName) {
        this.name = name;
        this.engine = engine;
        this.nodesByName = Map.copyOf(nodesByName);
    }

    public String name() {
        return name;
    }

    public StabilizationEngine engine() {
        return engine;
    }

    public Map<String, Node<?>> nodesByName() {
        return nodesByName;
    }

    /**
     * Type-safe lookup of a node by name.
     *
     * @param name The node name.
     * @param <T>  Expected node type.
     * @return The node, or null if not found.
     */
    @SuppressWarnings("unchecked")
    public <T extends Node<?>> T node(String name) {
        return (T) nodesByName.get(name);
    }

    /**
     * Resolves a node name to its topological index (ID).
     * This ID is stable for the lifetime of the graph and should be used
     * for zero-allocation updates on the hot path.
     *
     * @param name The node name.
     * @return The node's topological index.
     * @throws IllegalArgumentException if the node format is unknown.
     */
    public int getNodeId(String name) {
        return engine.topology().topoIndex(name);
    }
}
