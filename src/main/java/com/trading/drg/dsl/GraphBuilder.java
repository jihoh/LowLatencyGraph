package com.trading.drg.dsl;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;

import com.trading.drg.api.*;
import com.trading.drg.fn.*;
import com.trading.drg.node.*;
import com.trading.drg.util.ScalarCutoffs;
import java.util.*;

/**
 * Graph Builder -- primary quant-facing API.
 *
 * This class provides a fluent API for defining the structure (topology) of the
 * dependency graph.
 *
 * Usage Pattern:
 * 1. Create a builder: GraphBuilder g = GraphBuilder.create("my_graph");
 * 2. Define sources: var src = g.scalarSource("src", 100.0);
 * 3. Define calculations: var calc = g.compute("calc", (x) -> x * 2, src);
 * 4. Build: StabilizationEngine engine = g.build();
 * ...
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
     * ...
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
     * @return The created ScalarSourceNode.
     */
    public ScalarSourceNode scalarSource(String name, double initialValue) {
        return scalarSource(name, initialValue, ScalarCutoffs.EXACT);
    }

    /**
     * Creates a scalar double source node with a custom cutoff strategy.
     *
     * @param name         Unique name of the node.
     * @param initialValue Starting value.
     * @param cutoff       Cutoff logic to determine if a value change should
     *                     propagate.
     * @return The created ScalarSourceNode.
     */
    public ScalarSourceNode scalarSource(String name, double initialValue, ScalarCutoff cutoff) {
        checkNotBuilt();
        var node = new ScalarSourceNode(name, initialValue, cutoff);
        register(node);
        sourceNames.add(name);
        return node;
    }

    /**
     * Creates a vector source node.
     *
     * @param name Unique name.
     * @param size Number of elements.
     * @return The created VectorSourceNode.
     */
    public VectorSourceNode vectorSource(String name, int size) {
        checkNotBuilt();
        var node = new VectorSourceNode(name, size);
        register(node);
        sourceNames.add(name);
        return node;
    }

    // ... (Vector sources omitted, they are fine)

    // ── Computed doubles (1, 2, 3, N inputs) ────────────────────

    /**
     * Defines a computation node with 1 input.
     *
     * @param name Unique name.
     * @param fn   Function: double -> double.
     * @param in   Input node (must implement ScalarValue).
     * @return The created computation node.
     */
    public ScalarCalcNode compute(String name, Fn1 fn, ScalarValue in) {
        return compute(name, ScalarCutoffs.EXACT, fn, in);
    }

    /**
     * Defines a computation node with 1 input and custom cutoff.
     */
    public ScalarCalcNode compute(String name, ScalarCutoff cutoff, Fn1 fn, ScalarValue in) {
        checkNotBuilt();
        // Create the node with a lambda that pulls from the input interface
        var node = new ScalarCalcNode(name, cutoff, () -> fn.apply(in.doubleValue()));
        register(node);
        // Explicitly record dependency
        addEdge(in.name(), name);
        return node;
    }

    /**
     * Defines a computation node with 2 inputs.
     */
    public ScalarCalcNode compute(String name, Fn2 fn, ScalarValue in1, ScalarValue in2) {
        return compute(name, ScalarCutoffs.EXACT, fn, in1, in2);
    }

    /**
     * Defines a computation node with 2 inputs and custom cutoff.
     */
    public ScalarCalcNode compute(String name, ScalarCutoff cutoff, Fn2 fn,
            ScalarValue in1, ScalarValue in2) {
        checkNotBuilt();
        var node = new ScalarCalcNode(name, cutoff,
                () -> fn.apply(in1.doubleValue(), in2.doubleValue()));
        register(node);
        addEdge(in1.name(), name);
        addEdge(in2.name(), name);
        return node;
    }

    public ScalarCalcNode compute(String name, Fn3 fn,
            ScalarValue in1, ScalarValue in2, ScalarValue in3) {
        return compute(name, ScalarCutoffs.EXACT, fn, in1, in2, in3);
    }

    public ScalarCalcNode compute(String name, ScalarCutoff cutoff, Fn3 fn,
            ScalarValue in1, ScalarValue in2, ScalarValue in3) {
        checkNotBuilt();
        var node = new ScalarCalcNode(name, cutoff,
                () -> fn.apply(in1.doubleValue(), in2.doubleValue(), in3.doubleValue()));
        register(node);
        addEdge(in1.name(), name);
        addEdge(in2.name(), name);
        addEdge(in3.name(), name);
        return node;
    }

    /**
     * Defines a computation node with N inputs.
     * ...
     */
    public ScalarCalcNode computeN(String name, ScalarValue[] inputs, FnN fn) {
        return computeN(name, ScalarCutoffs.EXACT, inputs, fn);
    }

    public ScalarCalcNode computeN(String name, ScalarCutoff cutoff,
            ScalarValue[] inputs, FnN fn) {
        checkNotBuilt();
        // Allocate scratch buffer once at build time.
        // NOTE: This scratch buffer is captured by the lambda.
        final double[] scratch = new double[inputs.length];
        var node = new ScalarCalcNode(name, cutoff, () -> {
            // Gather inputs into scratch buffer
            for (int i = 0; i < inputs.length; i++)
                scratch[i] = inputs[i].doubleValue();
            return fn.apply(scratch);
        });
        register(node);
        // Register all dependencies
        for (ScalarValue input : inputs)
            addEdge(input.name(), name);
        return node;
    }

    /**
     * computeVector: Creates a vector computation node.
     */
    public VectorCalcNode computeVector(String name, int size, double tolerance,
            Node<?>[] inputs, VectorFn fn) {
        checkNotBuilt();
        var node = new VectorCalcNode(name, size, tolerance, inputs, fn);
        register(node);
        for (Node<?> in : inputs)
            addEdge(in.name(), name);
        return node;
    }

    /**
     * Extract a single element from a vector node.
     */
    public ScalarCalcNode vectorElement(String name, VectorValue vec, int index) {
        checkNotBuilt();
        var node = new ScalarCalcNode(name, ScalarCutoffs.EXACT, () -> vec.valueAt(index));
        register(node);
        addEdge(vec.name(), name);
        return node;
    }

    // ── Map nodes ────────────────────────────────────────────────

    /**
     * Creates a MapNode for reporting/debugging.
     * Uses default tolerance of 1e-9.
     */
    public MapNode mapNode(String name, String[] keys, Node<?>[] inputs, MapComputeFn fn) {
        return mapNode(name, keys, inputs, fn, 1e-9);
    }

    public MapNode mapNode(String name, String[] keys, Node<?>[] inputs, MapComputeFn fn, double tolerance) {
        checkNotBuilt();
        var node = new MapNode(name, keys, inputs, fn, tolerance);
        register(node);
        for (Node<?> in : inputs)
            addEdge(in.name(), name);
        return node;
    }

    // ── Conditionals / signals ───────────────────────────────────

    /**
     * Creates a boolean condition node.
     */
    public BooleanNode condition(String name, ScalarValue input, DoublePredicate pred) {
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
    public ScalarCalcNode select(String name, BooleanNode cond,
            ScalarValue ifTrue, ScalarValue ifFalse) {
        checkNotBuilt();
        var node = new ScalarCalcNode(name, ScalarCutoffs.EXACT,
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
