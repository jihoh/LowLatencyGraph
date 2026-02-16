package com.trading.drg.core;

/**
 * Observability interface for monitoring the graph stabilization process.
 *
 * <p>
 * Implementations can be registered with the {@link StabilizationEngine} to
 * receive
 * callbacks during the stabilization cycle. This is primarily used for:
 * <ul>
 * <li><b>Profiling:</b> Measuring how long stabilization takes.</li>
 * <li><b>Debugging:</b> Tracing which nodes are being recomputed and why.</li>
 * <li><b>Metrics:</b> Counting evaluations per second.</li>
 * </ul>
 *
 * <p>
 * <b>Warning:</b> These callbacks are on the hot path. Reference
 * implementations should be
 * extremely lightweight to avoid degrading engine performance.
 */
public interface StabilizationListener {

    /**
     * Called immediately before a stabilization pass begins.
     *
     * @param epoch The incrementing revision number of the graph state.
     */
    void onStabilizationStart(long epoch);

    /**
     * Called after a specific node has finished its {@link Node#stabilize()}
     * method.
     *
     * @param epoch     Current graph epoch.
     * @param topoIndex The topological index of the node (0 to N-1).
     * @param nodeName  The human-readable name of the node.
     * @param changed   {@code true} if the node's output changed; {@code false}
     *                  otherwise.
     */
    void onNodeStabilized(long epoch, int topoIndex, String nodeName, boolean changed);

    /**
     * Called when a node fails to stabilize due to an exception.
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
     * @param nodesStabilized The total number of nodes that were re-evaluated.
     */
    void onStabilizationEnd(long epoch, int nodesStabilized);
}
