package com.trading.drg.util;

import com.trading.drg.core.Node;

/**
 * A type-safe handle for reading node values from outside the graph.
 *
 * <p>
 * Observers are wrappers around {@link Node} references, offering a clean API
 * for
 * external components (UI, reports, downstream systems) to read graph outputs.
 *
 * @param <T> The type of value observed.
 */
public final class Observer<T> {
    private final Node<T> node;

    public Observer(Node<T> node) {
        this.node = node;
    }

    /** Returns the current stabilized value. */
    public T value() {
        return node.value();
    }

    public String nodeName() {
        return node.name();
    }
}
