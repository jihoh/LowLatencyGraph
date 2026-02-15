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

## 2. Architecture

### 2.1 Data Flow

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

### 2.2 Core Components

| Component | Role | Optimization |
|-----------|------|--------------|
| `GraphBuilder` | Fluent API to define topology. | Compiles to CSR (Compressed Sparse Row) format. |
| `StabilizationEngine` | Runtime engine. | Flat array traversal, pre-allocated dirty bits. |
| `TopologicalOrder` | Static graph structure. | Cache-friendly array-based adjacency. |
| `GraphEvent` | Disruptor event holder. | Uses `int nodeId` to avoid String lookups/allocations. |
| `GraphPublisher` | Bridge to Disruptor. | Uses `Node<?>[]` for O(1) source access. |

---

## 3. Usage

### 3.1 Defining a Graph

```java
var g = GraphBuilder.create("pricing_engine");

// 1. Sources (Market Data)
var bid = g.doubleSource("Mkt.Bid", 99.50);
var ask = g.doubleSource("Mkt.Ask", 100.50);

// 2. Logic (Lambdas - allocated once at build time)
var mid = g.compute("Calc.Mid", (b, a) -> (b + a) / 2.0, bid, ask);
var spread = g.compute("Calc.Spread", (a, b) -> a - b, ask, bid);

// 3. Build
GraphContext context = g.buildWithContext();
StabilizationEngine engine = context.engine();
```

### 3.2 Improving Performance: Pre-resolving IDs

For zero-GC updates, look up node IDs during checking/startup:

```java
// Startup
int bidId = context.getNodeId("Mkt.Bid");
int askId = context.getNodeId("Mkt.Ask");

// Hot Path (e.g. inside Disruptor Producer)
event.setDoubleUpdate(bidId, 99.55, true, seqId);
```

### 3.3 Types of Nodes

- **`doubleSource`**: Scalar input.
- **`vectorSource`**: Array input (e.g., yield curves).
- **`compute`**: Scalar transformation (1 to N inputs).
- **`computeVector`**: Array transformation.
- **`mapNode`**: Key-value outputs (e.g., Greeks/Risk).
- **`condition` / `select`**: Boolean logic and multiplexing.

---

## 4. Zero-GC Implementation Details

To achieve zero garbage generation during the "stabilize" phase:

1.  **Topology Flattening**: The graph is converted to integer arrays (`int[] children`, `int[] ordering`). No iterator objects.
2.  **Primitive State**: Nodes store state in `double` fields or pre-allocated `double[]` arrays. No boxing (`Double`).
3.  **Pre-allocated Events**: `GraphEvent` is a mutable flyweight in the RingBuffer.
4.  **Integer IDs**: String names are resolved to `int` topological indices at startup. The hot path uses only integers.

### Benchmarks

| Scenario | Nodes | Throughput | Latency |
|----------|-------|------------|---------|
| Treasury Simulator | ~40 | > 2,000,000 ops/sec | < 500ns |

*(Measured on Apple M-series silicon, single threaded)*

---

## 5. Reference Examples

- **`TreasurySimulator3.java`**: Complete example of a Swap Pricer with 4-layer topology, using the Disruptor pattern and zero-GC updates.
- **`LLGraphTest.java`**: Comprehensive unit tests covering all node types and cycle detection.
