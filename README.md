# LLGraph

**Low-Latency Deterministic Replayable Graph Engine**

---

## 1. Overview

LLGraph is a high-performance, zero-allocation incremental computation framework designed for electronic trading systems. It models calculations as a directed acyclic graph (DAG) where nodes are computations and edges are explicit dependencies.

### Key Properties

- **Deterministic:** Same inputs always produce the same outputs.
- **Incremental:** Only "dirty" nodes and their downstream dependents are recomputed.
- **Zero-GC on the Hot Path:** No object allocation during stabilization.
- **Topology-Driven:** Structure is frozen at build time for maximum optimization (CSR layout).
- **Single-Threaded:** Designed for use within a lock-free LMAX Disruptor `EventHandler`.

**Performance:** ~100ns per stabilization for typical trading logic (10-20 nodes). >2 million stabilizations/sec on a single core.

---

## 2. Quant Cookbook

Real-world examples for quantitative developers.

### 2.1 Hello World: Mid Price
Simple scalar arithmetic.

```java
var g = GraphBuilder.create("mid_price");

// Sources
var bid = g.doubleSource("bid", 99.50);
var ask = g.doubleSource("ask", 100.50);

// Logic
var mid = g.compute("mid", (b, a) -> (b + a) / 2.0, bid, ask);
var spread = g.compute("spread", (a, b) -> a - b, ask, bid);

// Build & Run
var engine = g.build();
engine.markDirty("bid"); 
engine.markDirty("ask");
engine.stabilize();

System.out.println("Mid: " + mid.doubleValue()); // 100.0
```

### 2.2 Yield Curve Construction (Vector Operations)
Building a discount curve from raw rates using vector nodes. `VectorSourceNode` and `computeVector` handle arrays efficiently.

```java
var g = GraphBuilder.create("curve_builder");
int points = 4;
double[] tenors = { 0.25, 0.5, 1.0, 2.0 };

// Vector Source (Market Rates)
var rates = g.vectorSource("mkt.rates", points);

// Vector Compute (Discount Factors)
var dfCurve = g.computeVector("calc.df_curve", points, 1e-12,
    new Node<?>[]{ rates },
    (inputs, output) -> {
        var r = (VectorSourceNode) inputs[0];
        for (int i = 0; i < points; i++) {
            // Simple bootstraper logic
            output[i] = 1.0 / (1.0 + r.valueAt(i) * tenors[i]);
        }
    });

// Extract single point for downstream use
var df1y = g.vectorElement("df.1y", dfCurve, 2); // Index 2 is 1.0y
```

### 2.3 Options Greeks (MapNode)
Calculating multiple named outputs (Delta, Gamma, Vega) in a single node to share intermediate calculations.

```java
var g = GraphBuilder.create("option_pricer");
var spot = g.doubleSource("spot", 100.0);
var vol  = g.doubleSource("vol", 0.20);

// MapNode names outputs: "px", "delta", "gamma", "vega"
var pricer = g.mapNode("atm_call", 
    new String[]{ "px", "delta", "gamma", "vega" },
    new Node<?>[]{ spot, vol },
    (inputs, out) -> {
        double S = ((DoubleReadable)inputs[0]).doubleValue();
        double sigma = ((DoubleReadable)inputs[1]).doubleValue();
        
        // Shared calculations (d1, d2)
        double d1 = ...; 
        
        // Write outputs by name
        out.put("px",    S * N(d1) - K * N(d2));
        out.put("delta", N(d1));
        out.put("gamma", n(d1) / (S * sigma * sqrtT));
        out.put("vega",  S * n(d1) * sqrtT);
    });

// Downstream usage
var delta = pricer.get("delta"); // O(1) access
```

### 2.4 Circuit Breakers (Conditionals)
Using `condition` (BooleanNode) and `select` to switch behavior based on market regimes.

```java
var g = GraphBuilder.create("safety_logic");
var rawSpread = g.doubleSource("spread", 0.05);
var vol       = g.doubleSource("vol", 0.15);

// 1. Define Condition (Signal)
// Only signals TRUE if vol > 50%
var highVolMode = g.condition("is_high_vol", vol, v -> v > 0.50);

// 2. Conditional Logic
// If high vol, widen spread by 2x. Else use raw spread.
var safeSpread = g.select("safe_spread", 
    highVolMode, 
    g.compute("wide", s -> s * 2.0, rawSpread), // True logic
    rawSpread                                   // False logic
);
```

### 2.5 Portfolio Pricing (Templates)
Stamping out identical subgraphs for hundreds of instruments using helper methods (`TemplateFactory`).

```java
record SwapConfig(double notional, double fixedRate) {}
record SwapOutputs(CalcDoubleNode npv, CalcDoubleNode dv01) {}

// Template Definition
TemplateFactory<SwapConfig, SwapOutputs> swapTemplate = (b, prefix, cfg) -> {
    var npv = b.compute(prefix + ".npv", 
        (r) -> cfg.notional * (r - cfg.fixedRate), 
        rateNode);
    var dv01 = b.compute(prefix + ".dv01", ...);
    return new SwapOutputs(npv, dv01);
};

// Instantiation
var s1 = g.template("swap1", swapTemplate, new SwapConfig(10e6, 0.04));
var s2 = g.template("swap2", swapTemplate, new SwapConfig(5e6,  0.03));
```

### 2.6 Best Practices: Node Granularity
**Design Philosophy: Coarse-Grained Business Units**

A common mistake is treating the graph like an Abstract Syntax Tree (AST) where every `+`, `-`, and `*` is a node. This performs poorly. Instead, treat nodes as **Business Logic Units** (e.g., "Black-Scholes Pricer", "Risk Aggregator", "Signal Generator").

#### Why Coarse-Grained is Faster

1.  **The "Graph Tax"**: Every node introduces overhead:
    *   **Memory**: Metadata for dirty flags, child indices, and value storage.
    *   **Traversal**: The engine must iterate, check dirty bits, and look up children in the adjacency array.
    *   **Cache Misses**: Jumping between nodes evicts helpful data from L1/L2 CPU caches.

2.  **JIT Compilation Scope**:
    *   **Inside a Node**: The Java HotSpot Compiler (JIT) sees the entire lambda as a single block. It can use CPU registers, SIMD instructions (AVX), and dead-code elimination.
    *   **Between Nodes**: The JIT sees opaque method calls. It cannot easily optimize across these boundaries.

#### Example: The Cost of Granularity

**❌ Bad: "AST Style" (High Overhead)**
3 nodes, 3 dirty checks, 3 array lookups, 3 function calls.
```java
// Logic: (a + b) * c
var sum  = g.compute("sum",  (a, b) -> a + b, srcA, srcB);
var prod = g.compute("prod", (s, c) -> s * c, sum, srcC); 
```

**✅ Good: "Business Logic Style" (Zero Overhead)**
1 node. The math `(a + b) * c` compiles to a single FMA (Fused Multiply-Add) machine instruction.
```java
var result = g.compute("total_calc", 
    (a, b, c) -> (a + b) * c, 
    srcA, srcB, srcC
);
```

#### When to Split Nodes?
Only pay the "Graph Tax" when you buy something valuable:

1.  **Reusability**: Is the intermediate value (e.g., `fair_price`) needed by *multiple* other nodes (e.g., `execution_logic` AND `risk_report`)?
2.  **Frequency Mismatch**: Does one input update 1000x/sec while another updates 1x/hour?
    *   *Example*: `HeavyCalc(MarketData) + ConfigShift`.
    *   Split this so `ConfigShift` doesn't trigger the heavy calc when it changes.
3.  **Feedback Loops**: You need a cycle (e.g., `Time` -> `State` -> `NextTime`).

**Rule of Thumb**: Start with large, comprehensive nodes. Break them down only when architectural reuse demands it.

---

## 3. Architecture

### 3.1 Data Flow

```
[Disruptor Ring Buffer]
       │
       ▼
[GraphPublisher (EventHandler)]
       │
       │ (1) Read int nodeId from event (Zero-GC lookup)
       │ (2) Update primitive primitive source value
       │ (3) map.markDirty(nodeId)
       ▼
[StabilizationEngine]
       │
       │ (4) Walk topological order
       │ (5) Recompute dirty nodes
       │ (6) Propagate changes
       ▼
[Downstream Consumers]
```

### 3.2 Core Components

| Component | Role | Optimization |
|-----------|------|--------------|
| `GraphBuilder` | Fluent API to define topology. | Compiles to CSR (Compressed Sparse Row) format. |
| `StabilizationEngine` | Runtime engine. | Flat array traversal, pre-allocated dirty bits. |
| `GraphEvent` | Disruptor event holder. | Uses `int nodeId` to avoid String lookups. |
| `GraphPublisher` | Bridge to Disruptor. | Uses `Node<?>[]` for O(1) source access. |

---

## 4. Integration Guide

### 4.1 Lookup Caching (Critical for Perf)
To achieve zero-GC updates, resolve String names to Integers **once** at startup.

```java
// 1. Build Graph
var g = GraphBuilder.create("engine");
var bid = g.doubleSource("Mkt.Bid", 100.0);
var ctx = g.buildWithContext();

// 2. Cache IDs (Do this in your constructor)
int bidId = ctx.getNodeId("Mkt.Bid");

// 3. Hot Loop (Disruptor Producer)
// No strings allowed here!
public void onMarketData(MdUpdate md) {
    long seq = ringBuffer.next();
    try {
        GraphEvent e = ringBuffer.get(seq);
        // Use the cached int ID
        e.setDoubleUpdate(bidId, md.price, true, seq);
    } finally {
        ringBuffer.publish(seq);
    }
}
```

### 4.2 Snapshotting
Capture state for replay/debugging.

```java
var snapshot = new GraphSnapshot(engine.topology());
snapshot.capture(engine);
byte[] state = snapshot.exportBytes(); // Persist this

// ... crash happens ...

// Recovery
snapshot.restore(engine);
engine.stabilize(); // Deterministically restores exact state
```

---

## 5. Full Disruptor Integration

A complete end-to-end example showing how to drive the graph from a Disruptor RingBuffer.

### 5.1 The Setup (Startup Phase)

```java
// 1. Build the Graph
var g = GraphBuilder.create("market_data_engine");
var bidNode = g.doubleSource("Bid", 100.0);
var askNode = g.doubleSource("Ask", 101.0);
var midNode = g.compute("Mid", (b, a) -> (b + a) / 2, bidNode, askNode);

// 2. Build Context & Engine
GraphContext context = g.buildWithContext();
StabilizationEngine engine = context.engine();

// 3. CACHE NODE IDs (Critical for Zero-GC)
// Do this once at startup.
int bidId = context.getNodeId("Bid");
int askId = context.getNodeId("Ask");

// 4. Setup Disruptor
Disruptor<GraphEvent> disruptor = new Disruptor<>(
    GraphEvent::new,               // Event Factory
    1024,                          // Ring Buffer Size
    DaemonThreadFactory.INSTANCE,  // Thread Factory
    ProducerType.SINGLE,           // Producer Type
    new BlockingWaitStrategy()     // Wait Strategy
);

// 5. Connect the GraphPublisher
// GraphPublisher implements EventHandler<GraphEvent>
GraphPublisher publisher = new GraphPublisher(engine);

// Optional: Callback after each stabilization
publisher.setPostStabilizationCallback((epoch, count) -> {
    // Read outputs here (thread-safe, we are in the consumer thread)
    // No locking needed.
    System.out.println("New Mid: " + midNode.doubleValue());
});

// 6. Wire it up
disruptor.handleEventsWith(publisher::onEvent);
disruptor.start();
RingBuffer<GraphEvent> rb = disruptor.getRingBuffer();
```

### 5.2 The Producer (Hot Path)

This loop runs on the network I/O thread. It must be garbage-free.

```java
public void onMarketData(int instrumentId, double price) {
    long seq = rb.next();
    try {
        // 1. Get mutable event (Zero allocation)
        GraphEvent event = rb.get(seq);
        
        // 2. Map external ID to internal Graph ID (int -> int)
        // (Assuming you have a simple array/map for this)
        int graphNodeId = (instrumentId == 1) ? bidId : askId;

        // 3. Populate Event
        // batchEnd = true means "stabilize after this event"
        // batchEnd = false means "just update state, don't stabilize yet"
        boolean triggerStabilize = true; 
        
        event.setDoubleUpdate(graphNodeId, price, triggerStabilize, seq);
        
    } finally {
        // 4. Publish
        rb.publish(seq);
    }
}
```

### 5.3 Batching and Coalescing

The `GraphPublisher` is smart about batching.

1.  **User-driven Batching**: If you set `batchEnd=false` on the event, the engine marks the node dirty but DOES NOT stabilize. You can update 50 inputs and then set `batchEnd=true` on the 51st to trigger a single stabilization.
2.  **Disruptor Batching**: If the consumer falls behind, the Disruptor delivers a batch of events. The `GraphPublisher` processes all updates in the batch and stabilizes ONLY ONCE at the end of the batch (via the `endOfBatch` boolean from Disruptor).

This automatic coalescing is key to high throughput under load.

---

## 6. Implementation Details

To achieve zero garbage generation:

1.  **Topology Flattening**: The graph is converted to integer arrays (`int[] children`, `int[] ordering`). No iterator objects.
2.  **Primitive State**: Nodes store state in `double` fields or pre-allocated `double[]` arrays. No boxing (`Double`).
3.  **Pre-allocated Events**: `GraphEvent` is a mutable flyweight in the RingBuffer.

### Benchmarks

| Scenario | Nodes | Throughput | Latency |
|----------|-------|------------|---------|
| Treasury Simulator | ~40 | > 2,000,000 ops/sec | < 500ns |

*(Measured on Apple M-series silicon, single threaded)*
