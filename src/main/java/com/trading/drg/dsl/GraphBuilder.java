package com.trading.drg.dsl;

import com.trading.drg.api.Node;
import com.trading.drg.api.ScalarCutoff;
import com.trading.drg.api.ScalarValue;
import com.trading.drg.api.VectorValue;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.engine.TopologicalOrder;
import com.trading.drg.fn.DoublePredicate;
import com.trading.drg.fn.Fn1;
import com.trading.drg.fn.Fn2;
import com.trading.drg.fn.Fn3;
import com.trading.drg.fn.FnN;
import com.trading.drg.fn.TemplateFactory;
import com.trading.drg.fn.VectorFn;
import com.trading.drg.node.BooleanNode;
import com.trading.drg.node.ScalarCalcNode;
import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.node.VectorCalcNode;
import com.trading.drg.node.VectorSourceNode;
import com.trading.drg.util.ScalarCutoffs;
import java.util.*;

/**
 * Fluent API for defining the dependency graph topology.
 * <p>
 * Used to construct nodes, define relationships, and compile the final engine.
 */
public final class GraphBuilder {

    // Accumulate nodes and edges in lists before compiling to CSR format
    private final List<Node> nodes = new ArrayList<>();
    private final Map<String, Node> nodesByName = new HashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<String> sourceNames = new ArrayList<>();

    // Flag to prevent modification after building
    private boolean built;

    private GraphBuilder() {
    }

    /**
     * Creates a new GraphBuilder instance.
     * ...
     */
    public static GraphBuilder create() {
        return new GraphBuilder();
    }

    // ── Sources ──────────────────────────────────────────────────

    /**
     * Creates a scalar source node with EXACT cutoffs.
     *
     * @param name         Unique node name.
     * @param initialValue Initial value.
     * @return The created source node.
     */
    public ScalarSourceNode scalarSource(String name, double initialValue) {
        return scalarSource(name, initialValue, ScalarCutoffs.EXACT);
    }

    /**
     * Creates a scalar source node with a custom cutoff strategy.
     *
     * @param name         Unique node name.
     * @param initialValue Initial value.
     * @param cutoff       Cutoff strategy for propagation.
     * @return The created source node.
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
     * Defines a computation node with 1 scalar input.
     *
     * @param name Unique node name.
     * @param fn   1-arity function.
     * @param in   Input scalar node.
     * @return The created calc node.
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
        var node = new ScalarCalcNode(name, cutoff, () -> fn.apply(in.value()))
                .withStateExtractor(fn);
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
                () -> fn.apply(in1.value(), in2.value()))
                .withStateExtractor(fn);
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
                () -> fn.apply(in1.value(), in2.value(), in3.value()))
                .withStateExtractor(fn);
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
    public ScalarCalcNode computeN(String name, FnN fn, ScalarValue... inputs) {
        return computeN(name, ScalarCutoffs.EXACT, fn, inputs);
    }

    public ScalarCalcNode computeN(String name, ScalarCutoff cutoff,
            FnN fn, ScalarValue... inputs) {
        checkNotBuilt();
        // Allocate scratch buffer once at build time.
        // NOTE: This scratch buffer is captured by the lambda.
        final double[] scratch = new double[inputs.length];
        var node = new ScalarCalcNode(name, cutoff, () -> {
            // Gather inputs into scratch buffer
            for (int i = 0; i < inputs.length; i++)
                scratch[i] = inputs[i].value();
            return fn.apply(scratch);
        }).withStateExtractor(fn);
        register(node);
        // Register all dependencies
        for (ScalarValue input : inputs)
            addEdge(input.name(), name);
        return node;
    }

    /**
     * computeVector: Creates a vector computation node.
     */
    public VectorCalcNode computeVector(String name, int size, double tolerance, VectorFn fn, Node... inputs) {
        checkNotBuilt();
        var node = new VectorCalcNode(name, size, tolerance, fn, inputs);
        register(node);
        for (Node in : inputs)
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

    // ── Conditionals / signals ───────────────────────────────────

    /**
     * Creates a boolean condition node.
     */
    public BooleanNode condition(String name, ScalarValue input, DoublePredicate pred) {
        checkNotBuilt();
        var node = new BooleanNode(name, () -> pred.test(input.value()));
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
                () -> cond.booleanValue() ? ifTrue.value() : ifFalse.value());
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

    // ── Build ────────────────────────────────────────────────────

    /**
     * Compiles the graph into an executable {@link StabilizationEngine}.
     *
     * @return The executable engine.
     */
    public StabilizationEngine build() {
        checkNotBuilt();
        built = true;

        // Use the TopologicalOrder builder to handle sorting and compaction
        var topo = TopologicalOrder.builder();

        // Add all registered nodes
        for (Node node : nodes)
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
     * Retrieve a node by name during the build phase.
     * Useful for wiring up complex dependencies.
     */
    @SuppressWarnings("unchecked")
    public <T extends Node> T getNode(String name) {
        return (T) nodesByName.get(name);
    }

    // Internal helper to register a node and check for duplicates
    private void register(Node node) {
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
