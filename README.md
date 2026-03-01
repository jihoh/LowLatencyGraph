# CoreGraph API Guide

CoreGraph is a lock-free, Directed Acyclic Graph (DAG) computing engine built for High-Frequency Trading (HFT). The engine is strictly a computational model; while it does not leverage the LMAX Disruptor internally, the disruptor is commonly used externally to sequence market events into the graph. Together, they can process millions of market events per second without triggering Java Garbage Collection on the hot path.

### Key Capabilities

*   **Ultra-Low Latency:** Optimized for low single-digit microsecond stabilization latency.
*   **Predictability:** **Zero-GC** (Garbage Collection) on the hot path. All memory is pre-allocated.
*   **Throughput:** Capable of processing millions of updates per second on a single thread. 
*   **Zero-Overhead Read:** Direct node value access for the main application thread without GC pressure.
*   **Safety:** Statically typed, cycle-free topology with explicit ownership.

### Use Cases

*   **Pricing:** Swaps, Bonds, and Futures pricing.
*   **Auto-Hedging:** Real-time delta and risk calculations.
*   **Signals:** Custom signals and technical indicators (RSI, EWMA, MACD) on tick data.
*   **Algos:** Custom trading algorithms.

## Architecture & Data Flow

CoreGraph is built on a **Single-Writer / Single-Reader** architecture optimized for CPU cache locality and eliminating thread contention.

The system is designed as a **Passive** graph engine. The application thread drives the execution, giving you full control over threading and batching.

```text
 [Application Thread]
         │
         │ (1) Update Source Nodes
         │     graph.update("nodeName", value);
         │
         │ (2) Call Stabilize
         ▼
 [StabilizationEngine]
         │
         │ (3) Walk Topological Order (BitSet sparse scan)
         │ (4) If (Node is Dirty):
         │         Recompute();
         │         Detect Change;
         │         Mark Children Dirty;
         ▼
 [Post-Stabilization]
         │
         │ (wait-free direct access as engine is stable)
         ▼
 [Reader / UI / Risk System]
         │
         │ (5) graph.getDouble("Node")
         │ (6) Read Consistent State
```

You can construct a graph topology via two primary methods:
1. **The JSON Compiler (Declarative) - RECOMMENDED** 
2. **The Java Fluent DSL (Programmatic)**

While the DSL is useful for writing test beds or highly specialized dynamic logic, **the JSON Compiler is strictly recommended for all production deployments**. It enforces clean separation between trading logic and engine execution without requiring binary recompilation.

Regardless of the method chosen, the output is a highly optimized `CoreGraph` engine ready for deployment.

---

## 0. Core Engine Concepts

To use CoreGraph effectively, you must understand a few core behaviors unique to its reactive architecture.

### Epochs
An **Epoch** is a single, discrete snapshot in time of the entire topological graph. Every node in the graph sits at a specific state during an Epoch. The engine guarantees that all node computations belonging to an Epoch observe exactly the same input states. There are no race conditions or partial updates halfway down the graph.

### Batching (LMAX Disruptor)
In high-throughput environments, executing the entire graph topography for every single micro-event (e.g., millions of tick updates unthrottled) is inefficient. Instead, `CoreGraph` relies on the LMAX Disruptor **Batching** mechanic. 

When a burst of events hits the input buffer, the engine quickly aggregates the dirty state flags of the impacted Source Nodes in a batch. 

### Stabilization
**Stabilization** is the engine's pulse. At the end of every event batch, the engine calls `stabilize()`. 
1. The engine checks the bitset to see exactly which Source Nodes were dirtied in the current batch.
2. It propagates a dirty bitwave recursively down the tree in O(K) time (where K is the number of impacted downstream nodes).
3. It creates a brand-new **Epoch** and fires the `stabilize()` methods of only the modified nodes in perfect topological order.
4. Nodes that haven't changed (or whose changes fell below their `"cutoff"` threshold) natively short-circuit, sparing CPU cycles.

---

## 1. Node Types & Capabilities

Before building a graph, it's important to understand the available nodes you can place into it.

### Source Nodes (The Inputs)
Source nodes have no parents. Their values are updated externally (e.g. by a market data feed) and their changes propagate downstream.
*   **`ScalarSourceNode`**: Holds a single primitive `double` value (e.g. a price).
*   **`VectorSourceNode`**: Holds a fixed-size contiguous array of primitive `double` values (e.g. a Yield Curve).

### Compute Nodes (The Logic)
Compute nodes take one or more parents as inputs, execute a mathematical function upon them, and emit a result.
*   **`ScalarCalcNode`**: Takes any number of scalar inputs and computes a new `double`.
*   **`VectorCalcNode`**: Takes one or more vector inputs, performs parallel operations on them, and emits a new vector.
*   **`BooleanNode`**: Takes an input and returns a `boolean` (e.g. `Value > Threshold`).

---

## 2. Java Programmatic Graph Building 

The `GraphBuilder` is a fluent Java DSL ideal for generating topologies programmatically or creating dynamic test beds.

### Example: Building a Basic Spread Calculator

```java

// 1. Initialize the builder
GraphBuilder builder = GraphBuilder.create();

// 2. Define your external input sources
ScalarSourceNode leg1 = builder.scalarSource("US_10Y_Yield", 4.15);
ScalarSourceNode leg2 = builder.scalarSource("US_02Y_Yield", 4.85);

// 3. Define computation logic referencing those sources
ScalarCalcNode spread = builder.compute("2Y_10Y_Spread", new Spread(), leg2, leg1);

// 4. (Optional) Chain further computations
ScalarCalcNode avgSpread = builder.compute("Avg_Spread", new Ewma(0.1), spread);

// 5. Compile the topology into an executable engine
StabilizationEngine engine = builder.build();

// 6. Inject live data (update source nodes by name)
leg1.update(4.20);
leg2.update(4.88);

// 7. Flush the system (recomputes only dirty nodes)
engine.stabilize();

// 8. Read results (zero-allocation primitive read)
System.out.println("Spread: " + spread.value()); // Returns 0.68
```

### Example: Using Vectors

```java

GraphBuilder builder = GraphBuilder.create();

// Create a 3-point Yield Curve [1Y, 5Y, 10Y]
VectorSourceNode yieldCurve = builder.vectorSource("Curve", 3); 

// Populate headers for the GUI dashboard
yieldCurve.withHeaders(new String[]{"1Y", "5Y", "10Y"});

// Update values atomically
yieldCurve.update(new double[]{ 4.1, 3.8, 3.9 });
```

### Example: Boolean Conditions

```java

GraphBuilder builder = GraphBuilder.create();
ScalarSourceNode pnlFeed = builder.scalarSource("PnL", 0.0);
ScalarCalcNode zScore = builder.compute("CurrentZ", new ZScore(20), pnlFeed);

// Creates a boolean node that flips when Z-Score > 3.0
BooleanNode isExtreme = builder.condition("IsExtreme", zScore, v -> v > 3.0);

// Select between two values based on the condition
ScalarCalcNode output = builder.select("Output", isExtreme, pnlFeed, zScore);
```

---

## 3. JSON Declarative Compilation

For production environments, graphs are typically defined externally via JSON files and hot-loaded by the `JsonGraphCompiler`. This allows Quants and Analysts to modify business logic without recompiling the Java binary.

### Example Schema (fx_arb.json)

```json
{
    "graph": {
        "name": "FX ARBITRAGE DEMO",
        "version": "1.0",
        "nodes": [
            {
                "name": "EUR_USD",
                "type": "scalar_source",
                "properties": {
                   "cutoff": "exact"
                }
            },
            {
                "name": "GBP_USD",
                "type": "scalar_source"
            },
            {
                "name": "EUR_GBP.Synthetic",
                "type": "spread",
                "description": "Calculates the implied cross-rate",
                "inputs": {
                    "a": "EUR_USD",
                    "b": "GBP_USD"
                }
            }
        ]
    }
}
```

### Loading and Running a JSON Graph

```java

// Load and compile the graph from a JSON file
CoreGraph graph = new CoreGraph("src/main/resources/fx_arb.json");

// Update source nodes
graph.update("EUR_USD", 1.1850);
graph.update("GBP_USD", 1.2650);

// Stabilize and read
graph.stabilize();
System.out.println("Spread: " + graph.getDouble("EUR_GBP.Synthetic"));
```

### Supported Cutoff Strategies

The `"cutoff"` property controls change-detection sensitivity:

| Value | Behavior |
|---|---|
| `"exact"` (default) | Propagates on any bit-level change |
| `"always"` | Always propagates (no cutoff) |
| `"never"` | Never propagates (frozen node) |
| `"absolute"` | Propagates if `|new - old| > tolerance` |
| `"relative"` | Propagates if relative change exceeds `tolerance` |

### Templates

Templates allow you to define reusable sub-graph patterns in your JSON definitions:

```json
{
    "graph": {
        "name": "Template Demo",
        "version": "1.0",
        "templates": [
            {
                "name": "spread_with_sma",
                "nodes": [
                    {
                        "name": "{{prefix}}.Spread",
                        "type": "spread",
                        "inputs": { "a": "{{leg1}}", "b": "{{leg2}}" }
                    },
                    {
                        "name": "{{prefix}}.SMA",
                        "type": "sma",
                        "inputs": { "a": "{{prefix}}.Spread" },
                        "properties": { "window": "{{window}}" }
                    }
                ]
            }
        ],
        "nodes": [
            { "name": "A", "type": "scalar_source" },
            { "name": "B", "type": "scalar_source" },
            {
                "name": "AB",
                "type": "template",
                "properties": {
                    "template": "spread_with_sma",
                    "prefix": "AB",
                    "leg1": "A",
                    "leg2": "B",
                    "window": 20
                }
            }
        ]
    }
}
```

---

## 4. Performance Tuning

When building nodes (either in Java or JSON), you can supply configurations to customize precision and propagation behavior.

**`"cutoff"` parameter:** 
Applied to Scalar nodes. If a new computed value differs from its old value by less than the cutoff threshold, the node suppresses its dirty flag. All downstream nodes are instantly halted, saving monumental amounts of CPU. Set tolerances to small values (e.g., `1e-6`) for high-frequency streams.

```json
"properties": {
  "cutoff": "absolute",
  "tolerance": 0.0001
}
```

---

## 5. Advanced: Custom Node Creation in Java

If the pre-built mathematical functions do not suit your needs, you can easily create custom nodes by implementing functional interfaces.

### Creating a Custom `Fn2` Node

```java

// 1. Implement the Fn2 interface (2 scalar inputs → 1 output)
public class RatioSpread implements Fn2 {
    @Override
    public double apply(double a, double b) {
        if (b == 0.0) return Double.NaN;
        return a / b;
    }
}

// 2. Use it in the DSL
GraphBuilder builder = GraphBuilder.create();
ScalarSourceNode src1 = builder.scalarSource("A", 10.0);
ScalarSourceNode src2 = builder.scalarSource("B", 5.0);
ScalarCalcNode result = builder.compute("Ratio", new RatioSpread(), src1, src2);
```

### Creating a Custom N-Input Node

```java

// 1. Implement FnN for variable-arity input
public class MyCustomAlgo implements FnN {
    @Override
    public double apply(double[] inputs) {
        if (inputs == null || inputs.length < 2) return Double.NaN;
        return (inputs[0] * inputs[1]) / (inputs.length > 2 ? inputs[2] : 1.0);
    }
}

// 2. Use computeN for N-input nodes
GraphBuilder builder = GraphBuilder.create();
ScalarSourceNode src1 = builder.scalarSource("A", 10.0);
ScalarSourceNode src2 = builder.scalarSource("B", 5.0);
ScalarSourceNode src3 = builder.scalarSource("C", 2.0);
ScalarCalcNode result = builder.computeN("MyAlgo", new MyCustomAlgo(), src1, src2, src3);
```

### Registering Custom Nodes for JSON

To make your custom Java node available to analysts writing JSON definitions, register it in `NodeRegistry.java`:

```java
// 1. Add to the NodeType enum
MY_ALGO(MyCustomAlgo.class),

// 2. Register in NodeRegistry.registerBuiltIns()
registerFnN(NodeType.MY_ALGO, p -> new MyCustomAlgo());
```

---

## 6. Advanced: Vector Operations and UI Labels

Vectors are powerful for representing Yield Curves, order books, or historical timeseries. You can configure vectors to auto-expand individual elements on the frontend dashboard.

### JSON Vector Definition with Auto-Expanding Labels

```json
{
    "name": "MarketYieldCurve",
    "type": "vector_source",
    "description": "Simulates a 5-point yield curve",
    "properties": {
        "size": 5,
        "auto_expand": true,
        "auto_expand_labels": ["1M", "3M", "6M", "1Y", "2Y"]
    }
}
```

When `auto_expand` is set to `true`, the compiler automatically generates `vector_element` child nodes for each element (e.g., `MarketYieldCurve.1M`, `MarketYieldCurve.3M`, ...), making them individually addressable in the graph.

---

## 7. Dashboard & Telemetry

CoreGraph includes a real-time web dashboard that visualizes the graph topology, node values, per-node latency profiles, JVM metrics, and backpressure telemetry.

### Wiring the Dashboard

```java

CoreGraph graph = new CoreGraph("src/main/resources/fx_arb.json");

// Wire the dashboard with telemetry
DashboardWiring dashboard = new DashboardWiring(graph)
    .enableNodeProfiling()      // Per-node nanosecond profiling
    .enableLatencyTracking()    // End-to-end stabilization latency
    .enableDashboardServer(8081); // Start the web server

// Visit http://localhost:8081 for the live dashboard
```

### Disruptor Backpressure Monitoring

When using the LMAX Disruptor, bind the ring buffer to get automatic backpressure telemetry:

```java
DashboardWiring dashboard = new DashboardWiring(graph)
    .enableNodeProfiling()
    .enableLatencyTracking()
    .bindDisruptorTelemetry(ringBuffer)  // Ring buffer fill % on the dashboard
    .enableDashboardServer(8081);
```

### Exporting Snapshots

To export the current state of the engine:
1. **Dashboard UI:** Click the **"Snapshot"** button on `http://localhost:8081`.
2. **HTTP API:** `GET /api/snapshot` returns a JSON payload with full topology and node values.

---

## 8. Reliability & Error Handling

CoreGraph implements a **Fail-Safe** pattern for all calculation nodes to ensure that a single node failure does not crash the entire graph engine.

Because `CoreGraph` avoids object allocation to achieve zero-GC execution, exceptions are strongly discouraged inside the hot-path `stabilize()` methods. Throwing exceptions forces the JVM to allocate stack-traces, destroying performance. Instead, the engine utilizes primitive `Double.NaN` semantics to propagate errors or invalid states.

### The `ErrorRateLimiter` Circuit Breaker

When a calculation node throws an unexpected exception:

1. **Safety Isolation:** All `ScalarCalcNode` computation is wrapped in `try-catch`. If an exception occurs, the node returns `Double.NaN`.
2. **NaN Propagation:** `Double.NaN` natively propagates downstream, marking all dependent values as "unhealthy" without stopping the engine.
3. **Circuit Breaker:** The `ErrorRateLimiter` logs the error *once*, then **opens the circuit**. For the next 1000ms (default), that node's computation will short-circuit to `Double.NaN` *before* attempting computation, guaranteeing **zero exception instantiations** during the penalty phase.

### Handling Unhealthy Values

Consumers reading from the graph output or WebSocket should check for `Double.isNaN(value)` to gracefully handle temporary upstream failures.

```java
// Correct implementation for a custom node
public class SafeDivider implements Fn2 {
    @Override
    public double apply(double numerator, double denominator) {
        if (denominator == 0.0) return Double.NaN; // Graceful failure
        return numerator / denominator;
    }
}
```

---

## 9. Consuming from LMAX Disruptor

To achieve millions of ticks per second, `CoreGraph` integrates with the LMAX Disruptor RingBuffer. The graph provides a `GraphAutoRouter` that routes event fields to graph nodes using zero-allocation Trie lookups.

### Complete Disruptor Integration Example

```java

// 1. Build the graph from JSON
CoreGraph graph = new CoreGraph("src/main/resources/fx_arb.json");

// 2. Initialize the LMAX Disruptor
Disruptor<FxTickEvent> disruptor = new Disruptor<>(
    FxTickEvent::new,
    1024,
    DaemonThreadFactory.INSTANCE,
    ProducerType.SINGLE,
    new YieldingWaitStrategy()
);

// 3. Create the auto-router
GraphAutoRouter router = new GraphAutoRouter(graph).registerClass(FxTickEvent.class);

// 4. Wire the handler
disruptor.handleEventsWith((event, sequence, endOfBatch) -> {
    router.route(event);              // Zero-GC field routing
    if (endOfBatch) {
        graph.stabilize();            // Batch stabilization
    }
});
RingBuffer<FxTickEvent> ringBuffer = disruptor.start();

// 5. Publish events
long seq = ringBuffer.next();
try {
    FxTickEvent evt = ringBuffer.get(seq);
    evt.EURUSD = 1.051;
} finally {
    ringBuffer.publish(seq);
}
```

### Zero-GC Auto Routing (`GraphAutoRouter`)

In HFT, parsing strings inside the Disruptor thread is a fatal error. `CoreGraph` provides an annotation-based `GraphAutoRouter` which internally builds a constant-time Trie map of your POJOs at startup. 

```java

public class MarketDataEvent {
    // Keys combine to form a path, e.g. "USD10Y.BTEC"
    @RoutingKey(order = 1) public String instrument;
    @RoutingKey(order = 2) public String venue;

    // Value fields map to nodes, e.g. "USD10Y.BTEC.bid"
    @RoutingValue            public double bid;
    @RoutingValue("ask_alt") public double ask; // Custom name override
}
```

When you call `router.route(event)`, the router navigates the pre-built Trie using raw String references from the POJO fields, entirely avoiding `String.concat()` and instantly updating the destination nodes with zero GC overhead.
