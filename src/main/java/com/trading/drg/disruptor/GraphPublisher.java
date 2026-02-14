package com.trading.drg.disruptor;

import java.util.*;

import com.trading.drg.core.Node;
import com.trading.drg.core.StabilizationEngine;
import com.trading.drg.core.TopologicalOrder;
import com.trading.drg.node.DoubleSourceNode;
import com.trading.drg.node.VectorSourceNode;

/**
 * Disruptor EventHandler that consumes GraphEvents and drives stabilization.
 *
 * <p>
 * This class acts as the bridge between the LMAX Disruptor ring buffer and the
 * graph's {@link StabilizationEngine}.
 * It is designed to run on a single dedicated thread (the "consumer" thread),
 * ensuring that all graph
 * logic is single-threaded and lock-free.
 *
 * <h3>Workflow</h3>
 * <ol>
 * <li>The Publisher (external thread) writes a {@link GraphEvent} into the ring
 * buffer.</li>
 * <li>The Disruptor sequences these events.</li>
 * <li>This {@code GraphPublisher} reads the event and updates the corresponding
 * source node in the graph.</li>
 * <li><b>Batching Optimization:</b> If multiple events are available in the
 * ring buffer, the Disruptor
 * will deliver them in a loop before flushing. The {@code GraphPublisher} only
 * triggers
 * {@link StabilizationEngine#stabilize()} at the end of such a batch (or if
 * valid {@code endOfBatch} flag is set).
 * This provides massive throughput improvements by amortizing the cost of graph
 * traversal.</li>
 * </ol>
 *
 * <h3>Sources</h3>
 * <p>
 * During construction, the publisher caches references to all source nodes to
 * enable
 * zero-allocation lookups during event processing.
 */
public final class GraphPublisher {
    private final StabilizationEngine engine;
    private final TopologicalOrder topology;

    // Fast lookup maps for source nodes.
    // We separate double and vector sources to avoid instance checks in the hot
    // path.
    private final Map<String, DoubleSourceNode> doubleSources = new HashMap<>();
    private final Map<String, VectorSourceNode> vectorSources = new HashMap<>();

    private PostStabilizationCallback postStabilize;

    /**
     * Creates a new GraphPublisher for the given engine.
     * Use this to implement your Disruptor's EventHandler.
     */
    public GraphPublisher(StabilizationEngine engine) {
        this.engine = engine;
        this.topology = engine.topology();

        // Pre-scan the topology to cache source nodes for fast access.
        for (int i = 0; i < topology.nodeCount(); i++) {
            if (topology.isSource(i)) {
                Node<?> node = topology.node(i);
                if (node instanceof DoubleSourceNode dsn)
                    doubleSources.put(dsn.name(), dsn);
                else if (node instanceof VectorSourceNode vsn)
                    vectorSources.put(vsn.name(), vsn);
            }
        }
    }

    /**
     * Sets a callback to be invoked after every stabilization cycle.
     * Useful for metrics, logging, or notifying downstream consumers.
     */
    public void setPostStabilizationCallback(PostStabilizationCallback cb) {
        this.postStabilize = cb;
    }

    /**
     * Process a single event from the ring buffer.
     * Call this from your Disruptor EventHandler.onEvent() method.
     *
     * @param event      The event carried by the ring buffer.
     * @param sequence   The sequence ID of the event.
     * @param endOfBatch Flag indicating if this is the last event in the current
     *                   batch.
     */
    public void onEvent(GraphEvent event, long sequence, boolean endOfBatch) {
        final String nodeName = event.nodeName();

        // 1. Apply the update to the source node
        if (event.isVectorUpdate()) {
            // Vector element update: O(1) lookup + array index update
            VectorSourceNode vsn = vectorSources.get(nodeName);
            if (vsn != null) {
                vsn.updateAt(event.vectorIndex(), event.doubleValue());
                // Mark dirty immediately, but don't propagate yet
                engine.markDirty(topology.topoIndex(nodeName));
            }
        } else {
            // Scalar double update: O(1) lookup
            DoubleSourceNode dsn = doubleSources.get(nodeName);
            if (dsn != null) {
                dsn.updateDouble(event.doubleValue());
                // Mark dirty immediately
                engine.markDirty(topology.topoIndex(nodeName));
            }
        }

        // 2. Drive stabilization if this is the end of a batch
        // This is the key optimization: we can process 1000s of market data updates
        // and only recompute the graph once if they arrive in a tight burst.
        if (event.isBatchEnd() || endOfBatch) {
            int n = engine.stabilize();
            if (postStabilize != null)
                postStabilize.onStabilized(engine.epoch(), n);
        }
    }

    /**
     * Callback interface for post-stabilization actions.
     */
    @FunctionalInterface
    public interface PostStabilizationCallback {
        /**
         * Called after the graph has stabilized.
         * 
         * @param epoch           The new epoch number.
         * @param nodesRecomputed Number of nodes that were re-evaluated.
         */
        void onStabilized(long epoch, int nodesRecomputed);
    }
}
