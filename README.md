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

## 5. Implementation Details

To achieve zero garbage generation:

1.  **Topology Flattening**: The graph is converted to integer arrays (`int[] children`, `int[] ordering`). No iterator objects.
2.  **Primitive State**: Nodes store state in `double` fields or pre-allocated `double[]` arrays. No boxing (`Double`).
3.  **Pre-allocated Events**: `GraphEvent` is a mutable flyweight in the RingBuffer.

### Benchmarks

| Scenario | Nodes | Throughput | Latency |
|----------|-------|------------|---------|
| Treasury Simulator | ~40 | > 2,000,000 ops/sec | < 500ns |

*(Measured on Apple M-series silicon, single threaded)*
