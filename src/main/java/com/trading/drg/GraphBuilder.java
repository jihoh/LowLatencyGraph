package com.trading.drg;

import com.trading.drg.core.*;
import com.trading.drg.fn.*;
import com.trading.drg.node.*;
import com.trading.drg.util.DoubleCutoffs;
import java.util.*;

/**
 * Graph Builder — primary quant-facing API.
 *
 * <p>
 * This class provides a fluent API for defining the structure (topology) of the
 * dependency graph.
 * It is designed to be used by quants and developers to specify:
 * <ul>
 * <li><b>Source Nodes:</b> External inputs like market data prices,
 * volatilities, or fixings.</li>
 * <li><b>Computation Nodes:</b> Logic blocks that transform inputs into outputs
 * (spreads, pricing models, Greeks).</li>
 * <li><b>Edges:</b> The dependencies between nodes.</li>
 * </ul>
 *
 * <h3>Usage Pattern</h3>
 * 
 * <pre>{@code
 * GraphBuilder g = ClaudeGraph.builder("my_graph");
 * var src = g.doubleSource("src", 100.0);
 * var calc = g.compute("calc", (x) -> x * 2, src);
 * StabilizationEngine engine = g.build();
 * }</pre>
 *
 * <p>
 * The builder is stateful and not thread-safe. Once {@link #build()} is called,
 * the builder
 * is invalidated and cannot be used to add more nodes.
 */
public final class GraphBuilder {
    private final String graphName;

    // Accumulate nodes and edges in lists before compiling to CSR format
    private final List<Node<?>> nodes = new ArrayList<>();
    private final Map<String, Node<?>> nodesByName = new HashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<String> sourceNames = new ArrayList<>();

    // Flag to prevent modification after building
    private boolean built;

    private GraphBuilder(String graphName) {
        this.graphName = graphName;
    }

    /**
     * Creates a new GraphBuilder instance.
     *
     * @param graphName A human-readable name for the graph, useful for logging and
     *                  debugging.
     * @return A new builder instance.
     */
    public static GraphBuilder create(String graphName) {
        return new GraphBuilder(graphName);
    }

    // ── Sources ──────────────────────────────────────────────────

    /**
     * Creates a scalar double source node with EXACT cutoffs.
     * Use this for market data inputs where every bit change matters.
     *
     * @param name         Unique name of the node.
     * @param initialValue Starting value.
     * @return The created DoubleSourceNode.
     */
    public DoubleSourceNode doubleSource(String name, double initialValue) {
        return doubleSource(name, initialValue, DoubleCutoffs.EXACT);
    }

    /**
     * Creates a scalar double source node with a custom cutoff strategy.
     *
     * @param name         Unique name of the node.
     * @param initialValue Starting value.
     * @param cutoff       Cutoff logic to determine if a value change should
     *                     propagate.
     * @return The created DoubleSourceNode.
     */
    public DoubleSourceNode doubleSource(String name, double initialValue, DoubleCutoff cutoff) {
        checkNotBuilt();
        var node = new DoubleSourceNode(name, initialValue, cutoff);
        register(node);
        sourceNames.add(name);
        return node;
    }

    /**
     * Creates a vector source node with default tolerance (1e-15).
     *
     * @param name Unique name of the node.
     * @param size Size of the vector.
     * @return The created VectorSourceNode.
     */
    public VectorSourceNode vectorSource(String name, int size) {
        return vectorSource(name, size, 1e-15);
    }

    /**
     * Creates a vector source node with custom tolerance.
     *
     * @param name      Unique name.
     * @param size      Vector size.
     * @param tolerance Absolute tolerance for change detection per element.
     * @return The created VectorSourceNode.
     */
    public VectorSourceNode vectorSource(String name, int size, double tolerance) {
        checkNotBuilt();
        var node = new VectorSourceNode(name, size, tolerance);
        register(node);
        sourceNames.add(name);
        return node;
    }

    // ── Computed doubles (1, 2, 3, N inputs) ────────────────────

    /**
     * Defines a computation node with 1 input.
     *
     * @param name Unique name.
     * @param fn   Function: double -> double.
     * @param in   Input node (must implement DoubleReadable).
     * @return The created computation node.
     */
    public CalcDoubleNode compute(String name, Fn1 fn, DoubleReadable in) {
        return compute(name, DoubleCutoffs.EXACT, fn, in);
    }

    /**
     * Defines a computation node with 1 input and custom cutoff.
     */
    public CalcDoubleNode compute(String name, DoubleCutoff cutoff, Fn1 fn, DoubleReadable in) {
        checkNotBuilt();
        // Create the node with a lambda that pulls from the input interface
        var node = new CalcDoubleNode(name, cutoff, () -> fn.apply(in.doubleValue()));
        register(node);
        // Explicitly record dependency
        addEdge(in.name(), name);
        return node;
    }

    /**
     * Defines a computation node with 2 inputs.
     */
    public CalcDoubleNode compute(String name, Fn2 fn, DoubleReadable in1, DoubleReadable in2) {
        return compute(name, DoubleCutoffs.EXACT, fn, in1, in2);
    }

    /**
     * Defines a computation node with 2 inputs and custom cutoff.
     */
    public CalcDoubleNode compute(String name, DoubleCutoff cutoff, Fn2 fn,
            DoubleReadable in1, DoubleReadable in2) {
        checkNotBuilt();
        var node = new CalcDoubleNode(name, cutoff,
                () -> fn.apply(in1.doubleValue(), in2.doubleValue()));
        register(node);
        addEdge(in1.name(), name);
        addEdge(in2.name(), name);
        return node;
    }

    public CalcDoubleNode compute(String name, Fn3 fn,
            DoubleReadable in1, DoubleReadable in2, DoubleReadable in3) {
        return compute(name, DoubleCutoffs.EXACT, fn, in1, in2, in3);
    }

    public CalcDoubleNode compute(String name, DoubleCutoff cutoff, Fn3 fn,
            DoubleReadable in1, DoubleReadable in2, DoubleReadable in3) {
        checkNotBuilt();
        var node = new CalcDoubleNode(name, cutoff,
                () -> fn.apply(in1.doubleValue(), in2.doubleValue(), in3.doubleValue()));
        register(node);
        addEdge(in1.name(), name);
        addEdge(in2.name(), name);
        addEdge(in3.name(), name);
        return node;
    }

    /**
     * Defines a computation node with N inputs.
     * Uses a pre-allocated scratch buffer to avoid allocation during compute.
     */
    public CalcDoubleNode computeN(String name, DoubleReadable[] inputs, FnN fn) {
        return computeN(name, DoubleCutoffs.EXACT, inputs, fn);
    }

    public CalcDoubleNode computeN(String name, DoubleCutoff cutoff,
            DoubleReadable[] inputs, FnN fn) {
        checkNotBuilt();
        // Allocate scratch buffer once at build time.
        // NOTE: This scratch buffer is captured by the lambda.
        final double[] scratch = new double[inputs.length];
        var node = new CalcDoubleNode(name, cutoff, () -> {
            // Gather inputs into scratch buffer
            for (int i = 0; i < inputs.length; i++)
                scratch[i] = inputs[i].doubleValue();
            return fn.apply(scratch);
        });
        register(node);
        // Register all dependencies
        for (DoubleReadable input : inputs)
            addEdge(input.name(), name);
        return node;
    }

    // ── Computed vectors ─────────────────────────────────────────

    /**
     * Defines a vector computation node.
     *
     * @param name      Node name.
     * @param size      Output vector size.
     * @param tolerance Change tolerance.
     * @param inputs    Array of dependency nodes.
     * @param fn        Vector function logic.
     * @return The new vector node.
     */
    public CalcVectorNode computeVector(String name, int size, double tolerance,
            Node<?>[] inputs, VectorFn fn) {
        checkNotBuilt();
        var node = new CalcVectorNode(name, size, tolerance, inputs, fn);
        register(node);
        for (Node<?> input : inputs)
            addEdge(input.name(), name);
        return node;
    }

    /**
     * Extract a single element from a vector node.
     * This creates a virtual edge that reads a specific index. Zero-cost accessor.
     */
    public CalcDoubleNode vectorElement(String name, VectorReadable vec, int index) {
        checkNotBuilt();
        var node = new CalcDoubleNode(name, DoubleCutoffs.EXACT, () -> vec.valueAt(index));
        register(node);
        addEdge(vec.name(), name);
        return node;
    }

    // ── Map nodes ────────────────────────────────────────────────

    /**
     * Creates a MapNode (Risk buckets, etc).
     */
    public MapNode mapNode(String name, String[] keys, Node<?>[] inputs, MapComputeFn fn) {
        return mapNode(name, keys, inputs, fn, 1e-12);
    }

    public MapNode mapNode(String name, String[] keys, Node<?>[] inputs,
            MapComputeFn fn, double tolerance) {
        checkNotBuilt();
        var node = new MapNode(name, keys, inputs, fn, tolerance);
        register(node);
        for (Node<?> input : inputs)
            addEdge(input.name(), name);
        return node;
    }

    // ── Conditionals / signals ───────────────────────────────────

    /**
     * Creates a boolean condition node.
     */
    public BooleanNode condition(String name, DoubleReadable input, DoublePredicate pred) {
        checkNotBuilt();
        var node = new BooleanNode(name, () -> pred.test(input.doubleValue()));
        register(node);
        addEdge(input.name(), name);
        return node;
    }

    /**
     * Selects between two inputs based on a boolean condition.
     * Acts like an electrical multiplexer.
     */
    public CalcDoubleNode select(String name, BooleanNode cond,
            DoubleReadable ifTrue, DoubleReadable ifFalse) {
        checkNotBuilt();
        var node = new CalcDoubleNode(name, DoubleCutoffs.EXACT,
                () -> cond.booleanValue() ? ifTrue.doubleValue() : ifFalse.doubleValue());
        register(node);
        addEdge(cond.name(), name);
        addEdge(ifTrue.name(), name);
        addEdge(ifFalse.name(), name);
        return node;
    }

    // ── Templates ────────────────────────────────────────────────

    public <C, T> T template(String prefix, TemplateFactory<C, T> factory, C config) {
        return factory.create(this, prefix, config);
    }

    // ── Scratch space (pre-allocated at build time) ──────────────

    public double[] scratchDoubles(int size) {
        return new double[size];
    }

    public double[][] scratchDoubles2D(int rows, int cols) {
        return new double[rows][cols];
    }

    public long[] scratchLongs(int size) {
        return new long[size];
    }

    // ── Build ────────────────────────────────────────────────────

    /**
     * Compiles the graph into an executable {@link StabilizationEngine}.
     * This process involves:
     * <ol>
     * <li>Topological sort of all nodes.</li>
     * <li>Cycle detection.</li>
     * <li>Conversion of list-based graph to array-based (CSR) formats.</li>
     * </ol>
     * 
     * @return The executable engine.
     */
    public StabilizationEngine build() {
        checkNotBuilt();
        built = true;

        // Use the TopologicalOrder builder to handle sorting and compaction
        var topo = TopologicalOrder.builder();

        // Add all registered nodes
        for (Node<?> node : nodes)
            topo.addNode(node);

        // Mark source nodes so the engine knows to clear their dirty flags
        for (String src : sourceNames)
            topo.markSource(src);

        // Add all edges
        for (Edge edge : edges)
            topo.addEdge(edge.from, edge.to);

        // Compile
        return new StabilizationEngine(topo.build());
    }

    /**
     * Builds and returns a GraphContext, which includes the engine and a name
     * lookup map.
     * Useful for applications that need to look up nodes by name at runtime (e.g.,
     * UI, diagnostics).
     */
    public GraphContext buildWithContext() {
        StabilizationEngine engine = build();
        return new GraphContext(graphName, engine, nodesByName);
    }

    /**
     * Retrieve a node by name during the build phase.
     * Useful for wiring up complex dependencies.
     */
    @SuppressWarnings("unchecked")
    public <T extends Node<?>> T getNode(String name) {
        return (T) nodesByName.get(name);
    }

    // Internal helper to register a node and check for duplicates
    private void register(Node<?> node) {
        if (nodesByName.containsKey(node.name()))
            throw new IllegalArgumentException("Duplicate node name: " + node.name());
        nodes.add(node);
        nodesByName.put(node.name(), node);
    }

    private void addEdge(String from, String to) {
        edges.add(new Edge(from, to));
    }

    private void checkNotBuilt() {
        if (built)
            throw new IllegalStateException("Graph already built");
    }

    public record Edge(String from, String to) {
    }
}
