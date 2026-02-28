package com.trading.drg.api;

/**
 * Observability interface for monitoring the graph stabilization process.
 * <p>
 * Implementations should be lightweight to avoid impacting hot-path execution.
 */
public interface StabilizationListener {

    /**
     * Called before a stabilization pass begins.
     *
     * @param epoch The incrementing revision number of the graph state.
     */
    void onStabilizationStart(long epoch);

    /**
     * Called after a specific node finishes its stabilize() method.
     *
     * @param epoch         Current graph epoch.
     * @param topoIndex     Topological index of the node.
     * @param nodeName      Human-readable name of the node.
     * @param changed       {@code true} if output changed significantly;
     *                      {@code false} otherwise.
     * @param durationNanos Nanoseconds taken to evaluate.
     */
    void onNodeStabilized(long epoch, int topoIndex, String nodeName, boolean changed, long durationNanos);

    /**
     * Called when a node fails to stabilize due to an unhandled exception.
     *
     * @param epoch     Current graph epoch.
     * @param topoIndex Topological index of the node.
     * @param nodeName  Name of the failing node.
     * @param error     The exception that occurred.
     */
    void onNodeError(long epoch, int topoIndex, String nodeName, Throwable error);

    /**
     * Called when the stabilization pass is fully complete.
     *
     * @param epoch           Current graph epoch.
     * @param nodesStabilized Total number of nodes re-evaluated this cycle.
     */
    void onStabilizationEnd(long epoch, int nodesStabilized);
}
