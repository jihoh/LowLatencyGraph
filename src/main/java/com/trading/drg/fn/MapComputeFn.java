package com.trading.drg.fn;

import com.trading.drg.api.Node;
import com.trading.drg.node.MapNode;

/**
 * Functional interface for Map node computations.
 *
 * <p>
 * Used to generate Key-Value outputs (e.g., risk reports, aggregated stats).
 * The {@link MapNode.MapWriter} acts as a flyweight accessor to write values
 * into the underlying storage.
 */
@FunctionalInterface
public interface MapComputeFn {
    /**
     * Computes the map values.
     *
     * @param inputs Dependencies.
     * @param output The writer interface to set key-value pairs.
     */
    void compute(Node<?>[] inputs, MapNode.MapWriter output);
}
