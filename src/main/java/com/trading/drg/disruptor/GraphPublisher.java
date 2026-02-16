package com.trading.drg.disruptor;

import com.trading.drg.core.Node;
import com.trading.drg.core.StabilizationEngine;
import com.trading.drg.core.TopologicalOrder;
import com.trading.drg.node.DoubleSourceNode;
import com.trading.drg.node.VectorSourceNode;

import lombok.extern.log4j.Log4j2;

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
@Log4j2
public final class GraphPublisher {
    private final StabilizationEngine engine;
    private final TopologicalOrder topology;

    // Fast lookup array for source nodes.
    // sourceNodes[i] is the source node at topological index i, or null if it's not
    // a source.
    // This allows O(1) array access in the hot path.
    private final Node<?>[] sourceNodes;

    private PostStabilizationCallback postStabilize;

    /**
     * Creates a new GraphPublisher for the given engine.
     * Use this to implement your Disruptor's EventHandler.
     */
    public GraphPublisher(StabilizationEngine engine) {
        this.engine = engine;
        this.topology = engine.topology();
        this.sourceNodes = new Node<?>[topology.nodeCount()];

        // Pre-scan the topology to cache source nodes for fast access.
        for (int i = 0; i < topology.nodeCount(); i++) {
            if (topology.isSource(i)) {
                sourceNodes[i] = topology.node(i);
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
        final int nodeId = event.nodeId();

        // 1. Apply the update to the source node
        if (nodeId >= 0 && nodeId < sourceNodes.length) {
            Node<?> node = sourceNodes[nodeId];
            if (node != null) {
                if (event.isVectorUpdate()) {
                    if (node instanceof VectorSourceNode vsn) {
                        vsn.updateAt(event.vectorIndex(), event.doubleValue());
                        engine.markDirty(nodeId);
                    }
                } else {
                    if (node instanceof DoubleSourceNode dsn) {
                        dsn.updateDouble(event.doubleValue());
                        engine.markDirty(nodeId);
                    }
                }
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
