# CoreGraph Developer API Guide

Welcome to the CoreGraph API documentation. This guide is tailored for building zero-allocation reactive graphs.

CoreGraph is a lock-free, Directed Acyclic Graph (DAG) computing engine originally built for High-Frequency Trading (HFT). The engine is strictly a computational model; while it does not leverage the LMAX Disruptor internally, the disruptor is commonly used externally to sequence market events into the graph. Together, they can process millions of market events per second without triggering Java Garbage Collection on the hot path.

### Key Capabilities

*   **Ultra-Low Latency:** Optimized for low single-digit microsecond stabilization latency on modern CPUs.
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
         │     node.updateDouble(value);
         │     engine.markDirty(nodeId);
         │
         │ (2) Call Stabilize
         ▼
 [StabilizationEngine]
         │
         │ (3) Walk Topological Order (int[] array scan)
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
2. It propagates a dirty bitwave recursively down the tree in O(K) time (where K is the number of impacted down-stream nodes).
3. It creates a brand-new **Epoch** and fires the `calculate()` methods of only the modified nodes in perfect topological order.
4. Nodes that haven't changed (or whose changes fell below their `"cutoff"` threshold) natively short-circuit, sparing CPU cycles.

---

## 1. Node Types & Capabilities

Before building a graph, it's important to understand the available nodes you can place into it.

### Source Nodes (The Inputs)
Source nodes have no parents. Their values are updated externally (e.g. by a market data feed) and their changes propagate downstream via the Disruptor.
*   **`ScalarSourceNode`**: Holds a single primitive `double` value (e.g. a stock price).
*   **`VectorSourceNode`**: Holds a fixed-size contiguous array of primitive `double` values (e.g. a Yield Curve).
*   **`BooleanSourceNode`**: Holds a single primitive `boolean` flag.

### Compute Nodes (The Logic)
Compute nodes take one or more parents as inputs, execute a mathematical function upon them, and emit a result.
*   **`ScalarCalcNode`**: Takes any number of scalar inputs and computes a new `double`.
    *   *Includes functions like:*, `Average`, `ZScore`, `Spread`, `Rsi`, `Macd`, `Correlation`.
*   **`VectorComputeNode`**: Takes one or more vector inputs, performs parallel operations on them, and emits a new vector.
*   **`BooleanCalcNode`**: Takes inputs and returns a `boolean` (e.g. `Value > Threshold`).
*   **`AlertNode`**: Takes a `boolean` input; when it transitions to `true`, it fires a user-defined event callback without generating garbage.

---

## 2. Java Programmatic Graph Building 

The `GraphBuilder` is a fluent Java DSL ideal for generating topologies programmatically or creating dynamic test beds.

### Example: Building a Basic Spread Calculator

```java
import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.fn.finance.Spread;

// 1. Initialize the builder
GraphBuilder builder = new GraphBuilder("Yield Curve Spread");

// 2. Define your external input sources
var leg1 = builder.scalarSource("US_10Y_Yield", 4.15);
var leg2 = builder.scalarSource("US_02Y_Yield", 4.85);

// 3. Define computation logic referencing those sources
var spread = builder.scalarCalc("2Y_10Y_Spread", new Spread(), leg2, leg1);

// 4. (Optional) Chain further computations
var avgSpread = builder.scalarCalc("Avg_Spread", new com.trading.drg.fn.finance.Ewma(10), spread);

// 5. Compile the topology and boot the engine
CoreGraph engine = builder.build();
engine.start();

// 6. Inject Live Data!
// Setting new values automatically cascades dirtiness to dependent nodes immediately.
leg1.update(4.20);
leg2.update(4.88);

// 7. Flush the system (pushes the dirty nodes through the evaluate lifecycle)
engine.stabilize();

// 8. Read results
System.out.println("Spread: " + spread.value()); // Returns 0.68
```

### Example: Using Vectors

```java
import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.api.VectorValue;

GraphBuilder builder = new GraphBuilder("Curve Demo");

// Create a 3-point Yield Curve [1Y, 5Y, 10Y]
var yieldCurve = builder.vectorSource("Curve", 3); 

// Populate headers for the GUI dashboard
yieldCurve.sourceHeaders(new String[]{"1Y", "5Y", "10Y"});

// Update values atomically
yieldCurve.update(new double[]{ 4.1, 3.8, 3.9 });
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
                   "cutoff": 1e-6
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
            },
            {
                "name": "ArbitrageOpportunity",
                "type": "boolean",
                "properties": {
                    "op": ">",
                    "threshold": 0.005
                },
                "inputs": {
                    "a": "EUR_GBP.Synthetic"
                }
            }
        ]
    }
}
```

### Supported JSON Types

The `"type"` field in the JSON corresponds specifically to predefined Java computations registered in the `NodeRegistry`. 

**Sources:**
*   `"scalar_source"`
*   `"vector_source"`
*   `"boolean_source"`

**Math & Finance:**
*   `"spread"`: Subtraction of two inputs (`a` - `b`)
*   `"z_score"`: Normalizes standard deviations. Requires `"window"` property.
*   `"beta"`: Calculates beta. Requires `"window"`.
*   `"correlation"`: Rolling correlation. Requires `"window"`.
*   `"sma"`: Simple Moving Average. Requires `"window"`.
*   `"ewma"`: Exponentially Weighted Moving Average. Requires `"window"`.

**Logic:**
*   `"boolean"`: Compares an input to a static threshold. Requires `"op"` (`>`, `<`, `=`, `!=`) and `"threshold"`.
*   `"alert"`: Watches a boolean. Triggers a side-effect globally.

---

## 4. Performance Tuning / Properties

When building nodes (either in Java or JSON), you can supply configurations to customize precision.

**`"cutoff"` parameter:** 
Applied to almost all Scalar nodes. If a new computed value differs from its old value by less than this `cutoff` amount, the node suppresses its dirty flag. All downstream nodes are instantly halted, saving monumental amounts of CPU. Set this to small values (e.g., `1e-6`) for high-frequency streams.

```json
"properties": {
  "cutoff": 0.0001
}
```

---

## 5. Advanced: Custom Node Creation in Java

If the pre-built mathematical functions do not suit your needs, you can easily create custom nodes by implementing functional interfaces and registering them. The engine enforces strong typing and memory safety through these interfaces.

### Creating a Custom Multi-Input `ScalarCalcNode`

```java
import com.trading.drg.fn.FnN;
import com.trading.drg.node.ScalarCalcNode;
import com.trading.drg.dsl.GraphBuilder;

// 1. Define your custom logic implementing FnN
public class MyCustomAlgo implements FnN {
    @Override
    public double apply(double[] inputs) {
        if (inputs == null || inputs.length < 2) return Double.NaN;
        // Example logic: (A * B) / C
        return (inputs[0] * inputs[1]) / (inputs.length > 2 ? inputs[2] : 1.0);
    }
}

// 2. Map it in the Builder
GraphBuilder builder = new GraphBuilder("Custom Graph");
var src1 = builder.scalarSource("A", 10.0);
var src2 = builder.scalarSource("B", 5.0);
var src3 = builder.scalarSource("C", 2.0);

// 3. Inject it! The engine handles all the zero-alloc array routing for you
var result = builder.scalarCalc("MyAlgo", new MyCustomAlgo(), src1, src2, src3);
```

### Registering Custom Nodes for JSON

To make your custom Java node available to analysts writing JSON definitions, register it inside the `NodeRegistry.java`:

```java
// Inside NodeRegistry constructor
registerFactory(NodeType.MY_ALGO, (name, props, deps) -> {
    var fn = new MyCustomAlgo();
    return new ScalarCalcNode(name, JsonGraphCompiler.parseCutoff(props),
            () -> fn.apply(deps)) // Note: apply(Object[]) wrapper for abstract topologies
            .withStateExtractor(fn); // Optional: if your node tracks internal state
});
```

---

## 6. Advanced: Vector Operations and UI Labels

Vectors are powerful for representing Yield Curves, order books, or historical timeseries. You can configure vectors to auto-expand their headers on the frontend UI for beautiful matrix displays.

### JSON Vector Definition with Auto-Expanding Labels

```json
{
    "name": "MarketYieldCurve",
    "type": "vector_source",
    "description": "Simulates a 5-point yield curve",
    "properties": {
        "size": 5,                 // The immutable array size
        "auto_expand": true,       // Tell the UI to split this vector out
        "auto_expand_labels": [    // Column titles for the dashboard
            "1M", "3M", "6M", "1Y", "2Y"
        ]
    }
}
```

---

## 7. Engine Telemetry and Event Listeners

`CoreGraph` runs on the LMAX Disruptor and exposes extreme low-level telemetry for monitoring health, latency, and CPU cycles out-of-the-box.

### Attaching Listeners via the DSL Builder

```java
import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.util.LatencyProfileListener;
import com.trading.drg.util.NodeProfileListener;
import com.trading.drg.util.DisruptorBackpressureLogger;

GraphBuilder builder = new GraphBuilder("Telemetry Graph");
// ... define nodes ...

// 1. Node Profiling: Tracks exactly how many Nanoseconds each individual Math Node consumes
// Requires `debug: true` on the CoreGraph instantiation, or use builder.debug(true) if supported
NodeProfileListener npl = new NodeProfileListener();
builder.withProfiler(npl);

// 2. Latency Profiling: Tracks end-to-end time-of-flight from Event dispatch to Stabilization
LatencyProfileListener lpl = new LatencyProfileListener();
builder.withLatencyListener(lpl);

CoreGraph engine = builder.build();

// 3. Backpressure Monitoring: Tracks how full the LMAX RingBuffer is getting. If this spikes > 0%, the frontend is too slow!
DisruptorBackpressureLogger backpressureLogger = new DisruptorBackpressureLogger();
engine.addPostCommitListener(backpressureLogger);

engine.start();

// After traffic flows, read exact stats:
System.out.println("E2E Latency: " + lpl.lastLatencyMicros() + " μs");
```

---

## 8. Reliability & Error Handling

CoreGraph implements a **Fail-Safe** pattern for all calculation nodes (`Fn` implementations) to ensure that a single node failure does not crash the entire graph engine.

Because `CoreGraph` avoids object allocation to achieve zero-GC execution, exceptions are strongly discouraged inside the hot-path `calculate()` methods. Throwing exceptions forces the JVM to allocate stack-traces, destroying performance. Instead, the engine utilizes primitive `Double.NaN` (Not a Number) object semantics to propagate errors or invalid states.

### 8.1 The `ErrorRateLimiter` Circuit Breaker
When a calculation node mathematically fails or throws an unexpected exception, CoreGraph handles this defensively:

*   **Safety Isolation:** All `apply()` methods internally wrap their logic in `try-catch` blocks. If an exception escapes (e.g., division by zero, invalid input), the node catches it and returns `Double.NaN`.
*   **NaN Propagation:** `Double.NaN` natively propagates downstream in constant time, marking all dependent values as "unhealthy" without stopping the engine or requiring manual error handling.
*   **Zero-Allocation Circuit Breaker:** Creating a Java `Exception` is incredibly slow because the JVM allocates a full stack trace. To protect the **Zero-GC** guarantee of the hot-path, the `ErrorRateLimiter` acts as an active circuit breaker.
*   **Fast-Fail:** When a node throws an exception, the limiter logs the error *once*, then **opens the circuit** (`isCircuitOpen()`). For the next 1000ms (default), that specific node's `apply()` method will instantly short-circuit and return `Double.NaN` *before* attempting computation. This guarantees **zero exception instantiations** in the hot-loop during the penalty phase, fully preserving your throughput.

### 8.2 Handling Unhealthy Values
Consumers reading from the graph output or WebSocket should check for `Double.isNaN(value)` to gracefully handle temporary upstream failures or open circuits.

```java
// Correct standard implementation for a custom node
public class SafeDivider implements Fn2 {
    @Override
    public double apply(double numerator, double denominator) {
        if (denominator == 0.0) {
            return Double.NaN; // Graceful failure!
        }
        return numerator / denominator;
    }
}
```

---

## 9. Graph Exporting and Visualization

You can generate algorithmic visualizations of your compiled `CoreGraph` at any time programmatically. The system ships natively with a transpiler that converts the Java graph topography into rendering `Mermaid.js` syntax.

```java
import com.trading.drg.util.GraphExplain;

// Compile the topology and extract Mermaid markdown
CoreGraph engine = builder.build();
String mermaidMarkup = new GraphExplain(engine).toMermaid();

System.out.println(mermaidMarkup);
// Output Example:
// graph TD;
//   US10Y[US10Y] --> 2Y_10Y_Spread[Spread];
//   US02Y[US02Y] --> 2Y_10Y_Spread;
//   2Y_10Y_Spread --> Avg_Spread[Ewma];
```

This output can be directly pasted into GitHub READMEs, Notion Docs, or HTML `<div class="mermaid">` tags.

---

## 10. Custom Alerting Nodes

Alert Nodes do not compute Math, but rather fire Java `Runnables` (callbacks) when specific boolean sub-graphs flip from `false` to `true`. This relies on crossing semantics to prevent spam (it only fires exactly upon crossing threshold, not continuously while over it).

```java
import com.trading.drg.node.AlertNode;

// 1. You have a node that computes some metric
var zScore = builder.scalarCalc("CurrentZ", new ZScore(20), pnlFeed);

// 2. You build a boolean node assessing the state
var isExtreme = builder.booleanCalc("IsExtreme", ">", 3.0, zScore);

// 3. You attach an Alert Node providing a side-effect
var myCallback = new Runnable() {
    @Override
    public void run() {
        System.out.println("ALERT! Z-Score breached 3.0.");
    }
builder.addAlert("PnlAlert", isExtreme, myCallback);
```

---

## 11. Consuming from LMAX Disruptor

To achieve millions of ticks per second, `CoreGraph` requires you to push your market data events into an LMAX Disruptor RingBuffer. The engine natively provides an `EventHandler` that you can wire into your Disruptor's execution chain. 

The Graph handler will consume your objects, route the values into the pre-compiled `SourceNode` endpoints, and then call `CoreGraph.stabilize()` when the batch finishes.

### Setting up the Disruptor Wiring

```java
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import java.util.concurrent.Executors;

// 1. Build your topogaphy
GraphBuilder builder = new GraphBuilder("Live Graph");
var eurusd = builder.scalarSource("EUR_USD", 1.05);
var gbpusd = builder.scalarSource("GBP_USD", 1.25);
builder.scalarCalc("Spread", new Spread(), eurusd, gbpusd);

CoreGraph graph = builder.build();
graph.start();

// 2. Initialize your LMAX Disruptor
int bufferSize = 1024 * 64; // Power of 2
Disruptor<MarketDataEvent> disruptor = new Disruptor<>(
    MarketDataEvent::new, 
    bufferSize, 
    Executors.defaultThreadFactory(), 
    com.lmax.disruptor.dsl.ProducerType.SINGLE, 
    new YieldingWaitStrategy() // Extremely low latency wait strategy
);

// 3. Define the Handler that bridges LMAX and CoreGraph
// CoreGraph natively provides a GraphAutoRouter that uses a zero-allocation Trie
// to route your Pojo fields! See "Zero-GC Auto Routing" below.
EventHandler<MarketDataEvent> graphInjector = (event, sequence, endOfBatch) -> {
    // Zero-allocation, Zero-GC topological update based purely on Trie routing
    router.route(event);

    // Only stabilize the graph when the LMAX batch natively completes!
    if (endOfBatch) {
        graph.stabilize();
    }
};

// 4. Wire the handler and start processing
disruptor.handleEventsWith(graphInjector);
disruptor.start();

// 5. Publish events to the RingBuffer (Producer side)
long seq = disruptor.getRingBuffer().next();
try {
    MarketDataEvent evt = disruptor.getRingBuffer().get(seq);
    evt.symbol = "EUR_USD";
    evt.price = 1.051;
} finally {
    disruptor.getRingBuffer().publish(seq); // Instantly wakes up the consumer!
}
```

### Zero-GC Auto Routing (`GraphAutoRouter`)

In HFT, parsing strings inside the Disruptor thread is a fatal error. `CoreGraph` provides an annotation-based `GraphAutoRouter` which internally builds a constant-time `Trie` map of your POJOs at startup. 

By annotating your event classes, the router can natively map memory addresses sequentially to Graph nodes without creating strings.

```java
import com.trading.drg.api.GraphAutoRouter.RoutingKey;
import com.trading.drg.api.GraphAutoRouter.RoutingValue;

public class MarketDataEvent {
    // Keys defined in hierarchical order. 
    // They combine to form a path, e.g. "USD10Y.BTEC"
    @RoutingKey(order = 1)
    public String instrument;
    @RoutingKey(order = 2)
    public String venue;

    // The data fields. The router combines the routing key path with the
    // field name or explicit value override. 
    // E.g., it looks for a Node named "USD10Y.BTEC.bid"
    @RoutingValue
    public double bid;
    @RoutingValue(value = "custom_ask_name")
    public double ask;
}
```

When you call `router.route(marketEvent)` inside your `EventHandler`, the router recursively navigates the natively built Trie using the primitive memory structures of your POJO fields (`instrument` and `venue`), entirely avoiding `String.concat()` and instantly triggering the updates on the destination nodes.
