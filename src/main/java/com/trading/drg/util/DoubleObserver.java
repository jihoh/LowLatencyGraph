package com.trading.drg.util;

import com.trading.drg.node.DoubleNode;

/**
 * Specialized Observer for accessing primitive double values.
 *
 * <p>
 * Avoids boxing overhead when reading results from the graph.
 */
public final class DoubleObserver {
    private final DoubleNode node;

    public DoubleObserver(DoubleNode node) {
        this.node = node;
    }

    /** Returns the current stabilized value as a primitive double. */
    public double doubleValue() {
        return node.doubleValue();
    }

    public String nodeName() {
        return node.name();
    }
}
