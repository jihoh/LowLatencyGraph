package com.trading.drg.engine;

import com.trading.drg.api.Node;

/**
 * Zero-allocation view of nodes whose values changed during the last
 * stabilization pass.
 * <p>
 * All nodes (including sources) that returned {@code true} from
 * {@link Node#stabilize()} are tracked.
 * <p>
 * This object is reused across stabilization cycles; call {@link #clear()}
 * before each pass. Iteration is sparse O(K) using the same
 * {@code Long.numberOfTrailingZeros} pattern as the engine's dirty BitSet.
 *
 * <pre>{@code
 * // Usage after stabilize():
 * UpdatedNodes updated = engine.updatedNodes();
 * updated.forEach(node -> {
 *     // consume updated value...
 * });
 * }</pre>
 */
public final class UpdatedNodes {
    private final long[] changedBits;
    private final TopologicalOrder topology;
    private int count;

    UpdatedNodes(TopologicalOrder topology) {
        this.topology = topology;
        this.changedBits = new long[(topology.nodeCount() + 63) / 64];
    }

    /** Marks a node as changed. Called by the engine during stabilization. */
    void mark(int topoIndex) {
        changedBits[topoIndex >> 6] |= (1L << topoIndex);
        count++;
    }

    /** Resets all flags for the next stabilization cycle. Zero-allocation. */
    void clear() {
        for (int i = 0; i < changedBits.length; i++) {
            changedBits[i] = 0L;
        }
        count = 0;
    }

    /**
     * Visits every changed node in topological order.
     *
     * <pre>{@code
     * updated.forEach(node -> {
     *     if (node instanceof ScalarValue sv) {
     *         System.out.println(node.name() + " = " + sv.value());
     *     }
     * });
     * }</pre>
     *
     * @param action Callback receiving each changed node.
     */
    public void forEach(java.util.function.Consumer<Node> action) {
        final int n = topology.nodeCount();
        for (int w = 0; w < changedBits.length; w++) {
            long word = changedBits[w];
            while (word != 0L) {
                int bitIndex = Long.numberOfTrailingZeros(word);
                int ti = (w << 6) + bitIndex;
                if (ti >= n)
                    return;
                action.accept(topology.node(ti));
                word &= ~(1L << bitIndex);
            }
        }
    }

    /** Returns {@code true} if the node at {@code topoIndex} changed. */
    public boolean isChanged(int topoIndex) {
        return (changedBits[topoIndex >> 6] & (1L << topoIndex)) != 0;
    }

    /** Number of nodes that changed in the last stabilization pass. */
    public int count() {
        return count;
    }

    /** Resolves a topological index to its {@link Node} object. */
    public Node node(int topoIndex) {
        return topology.node(topoIndex);
    }
}
