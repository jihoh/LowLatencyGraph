package com.trading.drg.core;

import java.util.Map;

/**
 * A container holding the "live" state of a built graph.
 *
 * <p>
 * The {@code GraphContext} is returned by the builder after construction.
 * It provides access to:
 * <ul>
 * <li>The {@link StabilizationEngine} (for driving the graph).</li>
 * <li>A lookup map of nodes by name (for inspection, UI binding, or
 * verification).</li>
 * </ul>
 *
 * <p>
 * This separation ensures that the {@link TopologicalOrder} and
 * {@link StabilizationEngine}
 * stay focused on performance (using indices), while the Context provides the
 * human-friendly
 * "User Space" accessors.
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
}
