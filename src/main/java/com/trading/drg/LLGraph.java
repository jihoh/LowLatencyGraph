package com.trading.drg;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;
import com.trading.drg.dsl.*;
import com.trading.drg.wiring.*;
import com.trading.drg.node.*;

/**
 * ClaudeGraph — Deterministic Replayable Graph engine for pricing and risk.
 *
 * <h2>Philosophy</h2>
 * <p>
 * This library is built on the principles of <b>Zero-Dependency</b> and
 * <b>Zero-Allocation</b> (on the hot path).
 * It models financial pricing and risk calculations as a Directed Acyclic Graph
 * (DAG) where:
 * <ul>
 * <li><b>Nodes</b> represent computations (e.g., Black-Scholes formula, curve
 * interpolation).</li>
 * <li><b>Edges</b> represent explicit data dependencies.</li>
 * <li><b>Stabilization</b> is the process of propagating updates from source
 * nodes to all affected leaf nodes.</li>
 * </ul>
 *
 * <h3>Key Features</h3>
 * <ul>
 * <li><b>Deterministic:</b> Given the same sequence of inputs, the graph always
 * reaches the exact same state.</li>
 * <li><b>Replayable:</b> The {@link com.trading.drg.io.GraphSnapshot} mechanism
 * allows capturing the full state
 * of the engine and restoring it later for debugging or "what-if"
 * analysis.</li>
 * <li><b>LMAX Disruptor Ready:</b> The engine is designed to sit behind a ring
 * buffer for single-threaded,
 * lock-free execution. See
 * {@link com.trading.drg.disruptor.GraphPublisher}.</li>
 * </ul>
 */
public final class LLGraph {

    private LLGraph() {
        // Prevent instantiation of utility class
    }

    /**
     * Entry point: create a new graph builder.
     *
     * <p>
     * Use the returned {@link GraphBuilder} to define the topology of your graph
     * using a fluent API.
     *
     * @param graphName A descriptive name for the graph instance.
     * @return A new {@link GraphBuilder} instance.
     */
    public static GraphBuilder builder(String graphName) {
        return GraphBuilder.create(graphName);
    }
}
