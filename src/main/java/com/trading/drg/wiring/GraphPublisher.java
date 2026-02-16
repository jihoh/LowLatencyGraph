package com.trading.drg.wiring;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;

import com.trading.drg.api.Node;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.engine.TopologicalOrder;
import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.node.VectorSourceNode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Disruptor EventHandler that consumes GraphEvents and drives stabilization.
 *
 * This class acts as the bridge between the LMAX Disruptor ring buffer and the
 * graph's StabilizationEngine. It is designed to run on a single dedicated
 * thread
 * (the "consumer" or "reactor" thread).
 *
 * Key Responsibilities:
 * 1. Event Translation: Reads raw GraphEvent objects from the ring buffer.
 * 2. State Update: Locates the target SourceNode using an O(1) array lookup
 * (pre-computed during construction) and updates its state.
 * 3. Stabilization Trigger: Decides when to call engine.stabilize().
 *
 * Batching/Coalescing Optimization:
 * The Disruptor provides a boolean 'endOfBatch' flag. If false, it means more
 * events
 * are immediately available in the ring buffer. In this case, we simply update
 * the
 * source node state but DO NOT trigger stabilization.
 *
 * We only call stabilize() when:
 * - endOfBatch is true (the ring buffer is empty or we reached the end of a
 * chunk).
 * - OR the event explicitly requests it (event.isBatchEnd()).
 *
 * This allows the engine to process thousands of market data ticks in a single
 * "micro-batch" and then recompute the graph only once, providing massive
 * throughput
 * gains (amortized O(1) overhead per tick).
 */
public final class GraphPublisher {
    private static final Logger log = LogManager.getLogger(GraphPublisher.class);

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
        // 1. Apply the update to the source node
        if (nodeId < 0 || nodeId >= sourceNodes.length) {
            log.error("Received event for invalid nodeId: {} (max={})", nodeId, sourceNodes.length - 1);
            return;
        }

        Node<?> node = sourceNodes[nodeId];
        if (node == null) {
            log.error("Received event for non-source node: nodeId={} (type unknown or computed)", nodeId);
            return;
        }

        try {
            if (event.isVectorUpdate()) {
                if (node instanceof VectorSourceNode vsn) {
                    vsn.updateAt(event.vectorIndex(), event.doubleValue());
                    engine.markDirty(nodeId);
                } else {
                    log.error("Received vector update for non-VectorSourceNode: nodeId={} type={}",
                            nodeId, node.getClass().getSimpleName());
                }
            } else {
                if (node instanceof ScalarSourceNode dsn) {
                    dsn.updateDouble(event.doubleValue());
                    engine.markDirty(nodeId);
                } else {
                    log.error("Received double update for non-ScalarSourceNode: nodeId={} type={}",
                            nodeId, node.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            log.error("Error processing event for node {}: {}", nodeId, e.getMessage(), e);
            // Do not rethrow, to keep consumer thread alive.
            // We return here to avoid stabilizing a potentially inconsistent state,
            // though depending on the specific error, partial update might be desired.
            // For bad inputs (bounds check), returning is safe.
            return;
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
