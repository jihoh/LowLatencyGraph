# CoreGraph Developer Guide

## 1. Introduction

**CoreGraph** is a specialized, high-performance graph engine designed for **low-latency electronic trading**. It allows you to model complex pricing, risk, and signal logic as a **Directed Acyclic Graph (DAG)**.

Unlike traditional object-oriented systems where business logic is scattered across many classes and callback handlers, CoreGraph centralizes your logic into a single, deterministic, and easily testable structure defined by data.

### Key Capabilities

*   **Ultra-Low Latency:** Optimized for sub-microsecond stabilization latency on modern CPUs.
*   **Predictability:** **Zero-GC** (Garbage Collection) on the hot path. All memory is pre-allocated.
*   **Throughput:** Capable of processing millions of updates per second on a single thread. Native integration with **LMAX Disruptor**.
*   **Zero-Overhead Read:** Direct node value access for the main application thread without GC pressure.
*   **Safety:** Statically typed, cycle-free topology with explicit ownership.

### Use Cases

*   **Pricing:** Swaps, Bonds, and Futures pricing.
*   **Auto-Hedging:** Real-time delta and risk calculations.
*   **Signals:** Custom signals and technical indicators (RSI, EWMA, MACD) on tick data.
*   **Algos:** Custom trading algorithms.

---

## 2. Architecture

CoreGraph is built on a **Single-Writer / Single-Reader** architecture optimized for CPU cache locality and eliminating thread contention.

### 2.1 The Data Flow

The system is designed as a **Passive** graph engine. The application thread drives the execution, giving you full control over threading and batching.

```
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

### 2.2 Core Components

| Component | Responsibility | Optimization Strategy |
|-----------|----------------|-----------------------|
| `StabilizationEngine` | **Runtime Kernel**. Executes the graph logic. | **Zero-Allocation**. Uses `double[]` for values and flat `int[]` arrays for connectivity. Eliminates pointer chasing. |
| `GraphBuilder` | **Compiler**. Defines the topology. | Compiles the graph description into **CSR (Compressed Sparse Row)** format - flattened integer arrays for maximum cache locality. |
| `JsonGraphCompiler` | **Loader**. JSON -> Graph. | Allows defining topology in data, decoupling strategy configuration from code compilation. |

### 2.3 The Asynchronous Double-Buffer (Lock-Free I/O)

To export millions of calculated scalars to WebSocket dashboards or remote UI telemetry without allocating `String`s or fighting OS locks, CoreGraph implements a **Double-Buffering** architecture.

Most architectures build JSON by allocating `new SnapshotEvent()` POJOs and serializing them with Jackson, triggering massive Garbage Collection. CoreGraph explicitly avoids this:

1. **The Math Thread (Write):** Two flat `double[][]` arrays are pre-allocated at startup. When `stabilize()` finishes natively, the hot thread grabs the "inactive" slot, blindly copy-pasting its raw node values into the array. It then triggers an `AtomicInteger` lock-free flip. There are **zero object creations** and **zero synchronized locks**.
2. **The I/O Thread (Read):** A background WebSocket thread monitors the `AtomicInteger`. When flipped, it reads the active `double[]`.
3. **Zero-Allocation JSON:** The I/O thread uses a single pre-allocated `StringBuilder`, resetting its length to 0 on every tick: `jsonBuilder.setLength(0)`. It constructs the JSON payload by directly concatenating statically interned string constants (e.g. `{"values":`) and mathematically formatting the primitive `double`s natively into the reusable `char[]` buffer.

This architecture achieves $O(Wait-Free)$ thread transfer, guaranteeing that the mathematical hot-path never waits for the network card.

### 2.4 The "Epoch" Concept
Every time `stabilize()` is called, the engine increments an internal `epoch` counter.
*   This creates a distinct **Logical Time** step.
*   All nodes computed in Epoch `N` see a consistent snapshot of inputs from Epoch `N-1` or `N`.
*   This guarantees **Atomic Consistency**: You typically never see "half a curve update" or "half an arb". If an input changes, all dependent nodes are updated before any output is visible
.
### 2.4 Memory Layout (CSR Topology)
To keep the CPU cache hot during the critical `stabilize()` loop, CoreGraph avoids an object-oriented tree structure.
Instead of Nodes holding `List<Node>` pointers to their children, the engine builds a **Compressed Sparse Row (CSR)** topology:

```java
Node[] topoOrder;     // Nodes sorted by execution order
int[] childrenOffset; // Index ranges for each node's children
int[] childrenList;   // Flattened list of downstream dependencies
long[] dirtyWords;    // Sparse BitSet leveraging hardware intrinsics
```

When the engine walks the graph, it streams through these primitive arrays. Rather than visiting every node in an $O(N)$ sweep to check its boolean state, the engine uses `Long.numberOfTrailingZeros(dirtyWords[w])` to instantly hardware-jump strictly to the exact nodes that were updated in O(K) time complexity. This is significantly faster and more cache-friendly than chasing `Iterator` pointers or sweeping booleans.

### 2.5 Vector Operations and Sparsity
When dealing with large arrays like Yield Curves or Volatility Surfaces, CoreGraph uses `VectorNode` types. However, computation nodes (`Fn1`, `Fn2`, etc.) generally should **not** depend directly on the entire root vector unless they genuinely require every single element (like a PCA calculation).

Instead, you should use `vector_element` intermediaries to extract specific scalars:
1. **$O(K)$ Sparse Evaluation:** If a downstream node (like a Spread calculation) directly depends on a 100-tenor `MarketYieldCurve`, any single tick on *any* tenor marks the root vector dirty, forcing your Spread node to uselessly recompute. By using a `Yield2Y` (`vector_element`) intermediary, the Spread calculation is shielded and will *only* recompute if the specific 2Y or 1M tenors tick.
2. **Interface Segregation:** It allows your core calculation functions to remain purely scalar (`double`), keeping them decoupled from array indexing logic and highly reusable.
3. **Visual Transparency:** Explicit element extraction makes the graph topology self-documenting, showing exactly which risk algorithms depend on which specific tenors of a curve.

---

## 3. The CoreGraphDemo Walkthrough

The most effective way to understand CoreGraph is to walk through the `CoreGraphDemo` reference implementation. This demo simulates a **Triangular Arbitrage** strategy, calculating the spread between a direct currency pair and a synthetic cross rate.

### 3.1 The Topology (`tri_arb.json`)
The strategy logic is defined in `src/main/resources/tri_arb.json`. This strict separation means you can change parameters (like `alpha` for EWMA) or dependencies without recompiling the code.

```json
{
    "nodes": [
        { 
            "id": "EURUSD", 
            "type": "Source", 
            "initialValue": 1.0850 
        },
        { 
            "id": "USDJPY", 
            "type": "Source", 
            "initialValue": 145.20 
        },
        { 
            "id": "EURJPY", 
            "type": "Source", 
            "initialValue": 157.55 
        },
        
        {
            "id": "Arb.Spread",
            "type": "Compute",
            "operation": "tri_arb_spread",
            "inputs": ["EURUSD", "USDJPY", "EURJPY"]
        },
        {
            "id": "Arb.Spread.Ewma",
            "type": "Compute",
            "operation": "ewma",
            "properties": { "alpha": 0.1 },
            "inputs": ["Arb.Spread"]
        }
    ]
}
```

**Key Elements:**
*   `id`: The unique name of the node. Used for lookups and debugging.
*   `type`: `Source` (input) or `Compute` (calculated).
*   `operation`: Limits the logic to a registered function name.
*   `inputs`: List of dependencies by ID. The order matters and matches the `Fn` arguments.
*   `properties`: Static configuration passed to the `Fn` constructor (e.g., `alpha`).

### 3.2 The Logic (Explicit `Fn` Classes)

CoreGraph avoids cryptic lambdas in favor of explicit classes that implement the `FnN` interfaces. This approach provides:
1.  **Type Safety:** The compiler verifies the number of arguments (arity).
2.  **State Encapsulation:** Logic that needs history (like EWMA) keeps its state private.
3.  **Reusability:** The same class can be used in multiple graphs.
4.  **Testability:** You can unit test the logic in isolation from the graph engine.

#### Triangular Arbitrage Logic (`TriangularArbSpread.java`)
This class implements `Fn3`, meaning it takes 3 inputs and produces 1 output.

```java
package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn3;

public class TriangularArbSpread implements Fn3 {
    @Override
    public double apply(double eurUsd, double usdJpy, double eurJpy) {
        // 1. Calculate Synthetic EUR/JPY
        //    (EUR/USD) * (USD/JPY) = EUR/JPY
        double synthetic = eurUsd * usdJpy;
        
        // 2. Calculate Spread
        //    Direct - Synthetic
        return eurJpy - synthetic;
    }
}
```

#### Stateful Logic (`Ewma.java`)
This class implements `Fn1` (1 input, 1 output). Notice how it manages private state (`state`, `initialized`) without garbage collection overhead.

```java
package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn1;

public class Ewma implements Fn1 {
    private final double alpha;
    private double state;
    private boolean initialized = false;

    // Properties from JSON are passed to the constructor
    public Ewma(double alpha) {
        this.alpha = alpha;
    }

    @Override
    public double apply(double input) {
        // Handle First Tick
        if (!initialized) {
            state = input;
            initialized = true;
            return state;
        }
        
        // Classic EWMA Formula:
        // New = Alpha * Input + (1 - Alpha) * Old
        state = alpha * input + (1.0 - alpha) * state;
        return state;
    }
    
    // Optional: Reset state for replay scenarios
    public void reset() {
        initialized = false;
        state = 0.0;
    }
}
```

### 3.3 The Main Application (`CoreGraphDemo.java`)

Here is how you wire it all together in Java. This single `main` method demonstrates the full lifecycle: Initialization, Direct Updates, and Wait-Free Consumption.

```java
public class CoreGraphDemo {
    public static void main(String[] args) throws Exception {
        // Initialize CoreGraph 
        var graph = new CoreGraph("src/main/resources/tri_arb.json");
        var profiler = graph.enableNodeProfiling();
        var latencyListener = graph.enableLatencyTracking();

        // Simulation Loop
        Random rng = new Random(42);
        int updates = 10_000;

        for (int i = 0; i < updates; i++) {
            double shock = (rng.nextDouble() - 0.5) * 0.01;

            // Use direct values for the next step of the random walk
            double currentEurUsd = graph.getDouble("EURUSD");
            double currentUsdJpy = graph.getDouble("USDJPY");
            double currentEurJpy = graph.getDouble("EURJPY");

            if (i % 500 == 0) {
                graph.update("EURJPY", 158.0);
                graph.stabilize();
            } else {
                graph.update("EURUSD", currentEurUsd + shock);
                graph.update("USDJPY", currentUsdJpy + shock * 100);
                graph.update("EURJPY", currentEurJpy + shock * 100);
                // Trigger stabilization manually
                graph.stabilize();
            }

            if (i % 1000 == 0) {
                double spread = graph.getDouble("Arb.Spread");
                if (Math.abs(spread) > 0.05) {
                    // Log the state we just saw
                }
            }
        }

        // Get Latency Stats
        System.out.println("\n--- Global Latency Stats ---");
        System.out.println(latencyListener.dump());
        System.out.println("\n--- Node Performance Profile ---");
        System.out.println(profiler.dump());
    }
}
```

### 3.4 The Advanced Application (`DisruptorGraphDemo.java`)

For raw throughput bridging network sockets directly to the graph, use the built-in LMAX Disruptor reference integration:

```java
// Boot the high-performance telemetry dashboard on port 9090
CoreGraph graph = new CoreGraph("bond_pricer_template.json")
        .enableDashboardServer(9090);

// Bind the graph natively to an LMAX single-producer RingBuffer
Disruptor<MarketDataEvent> disruptor = new Disruptor<>(
        MarketDataEvent::new, 1024, DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, new BlockingWaitStrategy()
);

disruptor.handleEventsWith(new MarketDataEventHandler(graph));
```

This demo boots a unified websocket listener rendering a live Javascript dashboard (accessible at `http://localhost:9090`), capable of ingesting millions of binary quotes and visualizing millisecond micro-shock latencies in real-time.

### 3.5 Zero-Allocation Event Routing (`GraphAutoRouter`)

When dealing with thousands of nodes, manually binding string names like `graph.update("UST_2Y.Btec.bid", event.getBid())` is tedious, error-prone, and generates garbage via String concatenation.

CoreGraph provides `GraphAutoRouter<T>` to completely map your POJOs to the graph at startup using reflection. On the hot path, it navigates a Zero-GC Trie using the object's native String references, finding pre-cached topological `int` IDs instantly.

**1. Annotate your Event POJO:**
Use `@RoutingKey` to define the structural prefix, and `@RoutingValue` to map your fields:
```java
public class MarketDataEvent {
    @RoutingKey(order = 1) private String instrument; // e.g. "UST_2Y"
    @RoutingKey(order = 2) private String venue;      // e.g. "Btec"

    @RoutingValue("bid") private double bid;          // maps to -> "UST_2Y.Btec.bid"
    @RoutingValue("ask") private double ask;          // maps to -> "UST_2Y.Btec.ask"
}
```

**2. Register your Event Classes:**
You can register as many different Event payload types as you want. The router maintains a map of highly optimized zero-GC `RouterCache` Tries tailored specifically for each registered `Class`.

```java
GraphAutoRouter router = new GraphAutoRouter(graph)
    .registerClass(TradeEvent.class)
    .registerClass(QuoteEvent.class);
```

**3. Route complex Envelope feeds natively without strings or casting:**
If you have an `EnvelopeEvent` containing heterogeneous payloads coming off the network, you can blindly pass the active object to the router:

```java
// Inside your hot loop (e.g. Disruptor onEvent)
EnvelopeEvent envelope = event;
Object payload = envelope.getPayload(); // e.g. a QuoteEvent or TradeEvent instance

// Zero-allocation, dynamic dispatch purely based on Object.getClass()
router.route(payload); 
```

This single line elegantly updates every matched node natively in $O(1)$ constant time with absolutely zero memory allocations or `instanceof` checks!

---

## 4. Developing Custom Logic

Extending CoreGraph with new financial logic is straightforward. You simply implement one of the `Fn` interfaces located in `com.trading.drg.api`.

### Supported Interfaces

*   `Fn1`: `double apply(double a)`
*   `Fn2`: `double apply(double a, double b)`
*   `Fn3`: `double apply(double a, double b, double c)`
*   `FnN`: `double apply(double[] inputs)` - Generic N-ary function.

### Example: Harmonic Mean (FnN)
Calculating the Harmonic Mean of an array of inputs (useful for averaging execution prices or P/E ratios).

```java
import com.trading.drg.fn.FnN;

public class HarmonicMean implements FnN {
    @Override
    public double apply(double[] inputs) {
        if (inputs.length == 0) return 0.0;
        
        double sumInverse = 0;
        
        // Use enhanced for-loop or indexed loop
        // Avoid streaming APIs (Arrays.stream) to ensure Zero-GC.
        for (double val : inputs) {
            sumInverse += 1.0 / val;
        }
        
        return inputs.length / sumInverse;
    }
}
```

### Developing Stateful Functions

When your logic needs history (Rolling Max, Min, StdDev), follow these rules:

1.  **State Fields**: Declare `private` fields in your class to hold state elements (e.g., `double sum`, `double sumSq`, `double[] ringBuffer`).
2.  **Constructor**: Initialize arrays here. Never allocate in `apply()`.
3.  **Apply Logic**: Update your state and return the current value.
4.  **Reset**: Provide a method to clear state if you plan to support graph resets.

### Testing Custom Functions

Because `Fn` classes are just POJOs (Plain Old Java Objects), unit testing is trivial.
Ensure all calculators are fully unit tested

```java
@Test
void testEwma() {
    Ewma ewma = new Ewma(0.5);
    
    // T=0: Init
    assertEquals(100.0, ewma.apply(100.0), 0.001);
    
    // T=1: Input 110. Expected: 0.5*110 + 0.5*100 = 105
    assertEquals(105.0, ewma.apply(110.0), 0.001);
    
    // T=2: Input 105. Expected: 0.5*105 + 0.5*105 = 105
    assertEquals(105.0, ewma.apply(105.0), 0.001);
}
```

---

## 5. Configuration Reference (JSON)

 The `graph.json` file is the blueprint for your strategy.

### Root Object
```json
{
  "nodes": [ ... ]
}
```

### Node Object Schema

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Unique identifier for the node. Format `category.name` is recommended. |
| `type` | String | Yes | `Source` for inputs, `Compute` for calculations. |
| `operation` | String | No | Required for `Compute` nodes. Must match a registered `Fn` name. |
| `initialValue`| Double | No | Required for `Source` nodes. The starting value. |
| `inputs` | List\<String\>| No | List of Node IDs this node depends on. Order matches `Fn` args. |
| `properties` | Object | No | Key-value pairs passed to the `Fn` constructor (e.g., window sizes). |

### Example: RSI Configuration

```json
{
  "id": "sig.rsi_14",
  "type": "Compute",
  "operation": "rsi",
  "inputs": ["mkt.close_price"],
  "properties": {
    "period": 14
  }
}
```

Ensure your `Rsi` class has a constructor matching the properties map or a specific signature supported by the factory.

---

### 6.2 The Two Performance Listeners

CoreGraph uses two distinct performance tracking mechanisms via `StabilizationListener` implementations. They are kept separated intentionally to manage runtime overhead:

1. **`LatencyTrackingListener` (O(1) Overhead):**
   *   **What it tracks:** Global `stabilize()` latency (min/max/avg) and throughput.
   *   **Mechanism:** Only captures `System.nanoTime()` at the very start and very end of the stabilization cycle. The per-node observation is an empty `no-op`.
   *   **Use Case:** Safe for always-on production monitoring.

2. **`NodeProfileListener` (O(N) Overhead):**
   *   **What it tracks:** The specific min/max/avg execution time of every single function node individually.
   *   **Mechanism:** Looks up the `NodeStats` object in a `ConcurrentHashMap` for every node that successfully fires and updates the duration.
   *   **Use Case:** Development and benchmarking only. Disable in production hot-paths to avoid map lookup overhead.

*Note: You can safely attach both via the `CompositeStabilizationListener` managed within `CoreGraph`.*

### 6.3 Profiling Best Practices

Use **Java Flight Recorder (JFR)** to verify the "Zero Allocation" promise.

1.  Start your application with JFR enabled.
2.  Record for 60 seconds during peak load.
3.  Open in Java Mission Control.
4.  Inspect **Method Profiling** -> `StabilizationEngine.stabilize()`.
5.  **Success Criteria:**
    *   **TLAB Allocations:** 0 bytes.
    *   **Boxed Integers/Doubles:** None.
    *   **String Concatenation:** None.

### 6.4 Performance Benchmarks

Sample latency statistics from `CoreGraphDemo` :

```text
--- Global Latency Stats ---
Metric               |      Value |   Avg (us) |   Min (us) |   Max (us)
------------------------------------------------------------------------------------------
Total Stabilizations |      10330 |       0.62 |       0.13 |     249.29


--- Node Performance Profile ---
Node Name                      |      Count |   Avg (us) |   Min (us) |   Max (us)
------------------------------------------------------------------------------------------
Arb.Spread                     |      10330 |       0.08 |       0.00 |      14.04
Arb.Spread.Ewma                |      10330 |       0.05 |       0.00 |       5.38
EURUSD                         |       9981 |       0.03 |       0.00 |       6.25
USDJPY                         |       9981 |       0.03 |       0.00 |       4.17
EURJPY                         |      10000 |       0.02 |       0.00 |       5.71
```

---

## 7. Reliability & Error Handling

CoreGraph implements a **Fail-Safe** pattern for all calculation nodes (`Fn` implementations) to ensure that a single node failure does not crash the entire graph engine.

### 7.1 Fail-Safe Pattern
*   **Safety:** All `apply()` methods are wrapped in `try-catch` blocks.
*   **Isolation:** If a node throws an exception (e.g., usage error, edge case), it returns `Double.NaN`.
*   **Propagation:** `Double.NaN` propagates downstream, marking dependent values as "unhealthy" without stopping the engine.
*   **Logging:** Errors are logged using `ErrorRateLimiter` to prevent log flooding (max 1 log every 1000ms per node type).

### 7.2 Handling Unhealthy Values
Consumers should check for `Double.isNaN(value)` when reading from the snapshot or within their own custom logic to handle upstream failures gracefully.