package com.trading.drg.api;

/**
 * Observability interface for monitoring the graph stabilization process.
 *
 * Implementations can be registered with the StabilizationEngine to receive
 * callbacks
 * during the stabilization cycle. This is the primary mechanism for:
 *
 * - Profiling: Measuring how long stabilization takes (end time - start time).
 * - Debugging: Tracing which nodes are being recomputed in a given cycle.
 * - Metrics: Counting evaluations per second or tracking the "blast radius" of
 * updates.
 *
 * Performance Warning:
 * These callbacks are executed on the hot path (the engine's stabilization
 * loop).
 * Reference implementations must be extremely lightweight. Any blocking I/O,
 * complex
 * calculations, or heavy object allocations here will directly degrade the
 * engine's
 * throughput and latency.
 */
public interface StabilizationListener {

    /**
     * Called immediately before a stabilization pass begins.
     *
     * @param epoch The incrementing revision number of the graph state.
     */
    void onStabilizationStart(long epoch);

    /**
     * Called after a specific node has finished its stabilize() method.
     *
     * This provides fine-grained visibility into the graph's execution.
     *
     * @param epoch     Current graph epoch.
     * @param topoIndex The topological index of the node (an integer from 0 to
     *                  N-1).
     * @param nodeName  The human-readable name of the node.
     * @param changed   true if the node's output changed significant enough to
     *                  propagate;
     *                  false otherwise.
     */
    void onNodeStabilized(long epoch, int topoIndex, String nodeName, boolean changed);

    /**
     * Called when a node fails to stabilize due to an unhandled exception.
     *
     * @param epoch     Current graph epoch.
     * @param topoIndex The topological index of the node.
     * @param nodeName  The name of the failing node.
     * @param error     The exception that occurred.
     */
    void onNodeError(long epoch, int topoIndex, String nodeName, Throwable error);

    /**
     * Called when the stabilization pass is fully complete.
     *
     * @param epoch           Current graph epoch.
     * @param nodesStabilized The total number of nodes that were re-evaluated this
     *                        cycle.
     */
    void onStabilizationEnd(long epoch, int nodesStabilized);
}
