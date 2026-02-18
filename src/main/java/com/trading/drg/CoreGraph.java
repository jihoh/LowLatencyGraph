package com.trading.drg;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.trading.drg.api.Node;
import com.trading.drg.api.StabilizationListener;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.io.GraphDefinition;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.io.JsonParser;
import com.trading.drg.wiring.GraphEvent;
import com.trading.drg.wiring.GraphPublisher;

import java.nio.file.Path;
import java.util.Map;

/**
 * A high-level wrapper around the DRG engine that simplifies JSON-based graph
 * instantiation
 * and event publishing.
 * <p>
 * This class handles:
 * <ul>
 * <li>Parsing JSON graph definitions</li>
 * <li>Compiling the graph using {@link JsonGraphCompiler} with built-in
 * factories</li>
 * <li>Setting up the {@link StabilizationEngine}</li>
 * <li>Configuring the LMAX Disruptor for single-threaded event processing</li>
 * <li>Providing simplified {@link #publish(String, double)} methods</li>
 * </ul>
 */
public class CoreGraph {
    private final StabilizationEngine engine;
    private final Map<String, Node<?>> nodes;
    private final Disruptor<GraphEvent> disruptor;
    private final RingBuffer<GraphEvent> ringBuffer;
    private final GraphPublisher publisher;
    private final com.trading.drg.util.LatencyTrackingListener listener;

    /**
     * Creates a new CoreGraph from a JSON file path string.
     *
     * @param jsonPath relative or absolute path to the JSON graph definition.
     */
    public CoreGraph(String jsonPath) {
        this(Path.of(jsonPath));
    }

    /**
     * Creates a new CoreGraph from a JSON file path.
     *
     * @param jsonPath Path to the JSON graph definition.
     */
    public CoreGraph(Path jsonPath) {
        // 1. Parse & Compile
        GraphDefinition graphDef;
        try {
            graphDef = JsonParser.parseFile(jsonPath);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to load graph definition from " + jsonPath, e);
        }
        var compiler = new JsonGraphCompiler().registerBuiltIns();
        var compiled = compiler.compile(graphDef);

        this.engine = compiled.engine();
        this.nodes = compiled.nodesByName();

        // Register default listener
        this.listener = new com.trading.drg.util.LatencyTrackingListener();
        this.engine.setListener(this.listener);

        // 2. Setup Disruptor
        this.disruptor = new Disruptor<>(
                GraphEvent::new,
                1024,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new BlockingWaitStrategy());

        this.publisher = new GraphPublisher(engine);
        this.disruptor.handleEventsWith((event, seq, end) -> publisher.onEvent(event, seq, end));
        this.ringBuffer = disruptor.getRingBuffer();
    }

    /**
     * Starts the disruptor thread. Must be called before publishing events.
     */
    public void start() {
        disruptor.start();
    }

    /**
     * Stops the disruptor thread.
     */
    public void stop() {
        disruptor.shutdown();
    }

    /**
     * Registers a listener to monitor stabilization events.
     *
     * @param listener The listener to register.
     */
    public void setListener(StabilizationListener listener) {
        engine.setListener(listener);
    }

    /**
     * Returns the underlying engine.
     *
     * @return The StabilizationEngine.
     */
    public StabilizationEngine getEngine() {
        return engine;
    }

    /**
     * Returns the graph publisher, allowing access to callbacks.
     *
     * @return The GraphPublisher.
     */
    public GraphPublisher getPublisher() {
        return publisher;
    }

    /**
     * Returns the default latency listener for metrics access.
     */
    public com.trading.drg.util.LatencyTrackingListener getLatencyListener() {
        return listener;
    }

    /**
     * Retrieves a node by name.
     *
     * @param name The name of the node in the JSON definition.
     * @param <T>  The expected type of the node.
     * @return The node, or null if not found.
     */
    @SuppressWarnings("unchecked")
    public <T> T getNode(String name) {
        return (T) nodes.get(name);
    }

    /**
     * Publishes a value update to a named node.
     * Defaults to marking the batch as ended (triggering stabilization).
     *
     * @param nodeName The name of the node to update.
     * @param value    The new value.
     */
    public void publish(String nodeName, double value) {
        publish(nodeName, value, true);
    }

    /**
     * Publishes a value update to a named node with manual batch control.
     *
     * @param nodeName The name of the node to update.
     * @param value    The new value.
     * @param batchEnd If true, stabilization will be triggered after this update.
     *                 If false, the update is queued until a subsequent
     *                 batchEnd=true event.
     * @throws IllegalArgumentException if the node name is not found in the
     *                                  topology.
     */
    public void publish(String nodeName, double value, boolean batchEnd) {
        // Look up ID directly from topology to avoid map lookups if possible,
        // but here we use the engine's topology which uses a map internally anyway.
        // If performance is critical, callers should cache the ID, but for ease of use
        // this is fine.
        int nodeId = engine.topology().topoIndex(nodeName);

        long seq = ringBuffer.next();
        try {
            GraphEvent event = ringBuffer.get(seq);
            event.setDoubleUpdate(nodeId, value, batchEnd, seq);
        } finally {
            ringBuffer.publish(seq);
        }
    }
}
