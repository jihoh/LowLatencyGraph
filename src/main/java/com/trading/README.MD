# CoreGraph

**Deterministic Replayable Graph Engine**

---

## 1. What Is CoreGraph

CoreGraph is an incremental computation framework. It models your calculations as a directed acyclic graph (DAG) where nodes are computations, edges are explicit dependencies, and updates propagate deterministically with minimal recomputation.

### Key Properties

- **Deterministic:** same inputs always produce the same outputs, in the same order.
- **Incremental:** only dirty nodes and their downstream dependents are recomputed.
- **Zero-allocation on the hot path:** no objects created during stabilization.
- **Static after compilation:** graph topology is frozen at startup. No runtime modifications.
- **Single-threaded:** runs on one Disruptor EventHandler thread. No locks, no synchronization.
- **Replayable:** snapshot source state, restore later, re-stabilize to reproduce exact outputs.

Typical performance: **90–250ns per stabilization** for a 10–20 node quoter graph. That is 4–10 million stabilizations per second on a single core.

---

## 2. Architecture

### 2.1 Data Flow

```
Ring Buffer (Disruptor)
    │
    ▼
GraphPublisher.onEvent()           ← single-threaded EventHandler
    ├── updates source nodes       ← e.g. DoubleSourceNode.updateDouble()
    ├── marks them dirty           ← engine.markDirty(topoIndex)
    ├── if batchEnd or endOfBatch:
    │       engine.stabilize()     ← one deterministic pass
    ▼
StabilizationEngine.stabilize()
    ├── walks topo order [0..N)
    ├── for each dirty node:
    │       node.stabilize()       ← recompute
    │       if changed (after cutoff):
    │           mark children dirty
    ▼
Post-stabilization callback        ← publish updated quotes, log, etc.
```

### 2.2 Core Components

| Component | What It Does | Hot Path? |
|-----------|-------------|-----------|
| `TopologicalOrder` | CSR-encoded static DAG. Pre-computed Kahn's sort at build time. Array-based adjacency for cache-friendly traversal. | Yes (read-only) |
| `StabilizationEngine` | Walks the topo order. Uses a pre-allocated `boolean[]` dirty array. Calls `node.stabilize()` and propagates based on cutoff. | Yes |
| `DoubleSourceNode` | Externally updated scalar double. Market data, params, fixings. `updateDouble()` is O(1), zero-alloc. | Yes |
| `VectorSourceNode` | Externally updated fixed-size `double[]`. Curve input quotes, vol surface rows. | Yes |
| `CalcDoubleNode` | Lambda-backed double computation. The workhorse for pricers, quoters, signals. | Yes |
| `CalcVectorNode` | Lambda-backed `double[]` computation. Curve builders, vol surfaces. | Yes |
| `MapNode` | Named key→double outputs. Risk buckets, Greeks. Pre-allocated parallel arrays. | Yes |
| `BooleanNode` | Signal/condition. Only propagates when the boolean flips. | Yes |
| `GraphBuilder` | Fluent API for constructing graphs in code. Produces `StabilizationEngine`. | No (build time) |
| `GraphPublisher` | Disruptor EventHandler. Consumes `GraphEvent`s, drives stabilization. | Yes |
| `GraphExplain` | Diagnostic tool. Dump topology, explain node state. NOT for hot path. | No |
| `LatencyTrackingListener` | Tracks stabilization latency (min/max/avg). Lightweight enough for prod. | Marginal |

### 2.3 Topological Order and CSR Encoding

At build time, nodes are topologically sorted using Kahn's algorithm. The result is flattened into arrays:

- `topoOrder[]` — nodes in topological order (sources first, leaves last)
- `childrenOffset[]` + `childrenList[]` — CSR (Compressed Sparse Row) adjacency. `childrenOffset[i]` gives the start index into `childrenList` for node i's children.
- `parentCount[]` — in-degree for each node
- `isSource[]` — boolean flag per node
- `nameToIndex` — `HashMap` for O(1) name→index lookup (build time and `markDirty` by name)

CSR means the children of a node are contiguous in memory. When propagating dirtiness, the inner loop is a tight sequential array scan. This is critical for L1 cache performance.

### 2.4 Stabilization Algorithm

The `stabilize()` method is the core hot path:

```
epoch++
for ti = 0 to nodeCount:
    if not dirty[ti]: continue       // skip clean nodes
    dirty[ti] = false                // clear before compute
    changed = node[ti].stabilize()   // recompute
    if changed:                      // cutoff said output is different
        for each child of ti:        // CSR array scan
            dirty[child] = true
```

The beauty of walking in topo order is that by the time we reach node N, all of N's parents have already been stabilized. There is no need for a work queue, no priority queue, no re-entrancy. One pass, front to back, done.

---

## 3. How It Is Optimized

### 3.1 Zero Allocation on the Hot Path

During stabilization, no Java objects are created. This means no young-gen GC pressure, no GC pauses interrupting your pricing loop.

| Component | How Zero-Alloc Is Achieved |
|-----------|---------------------------|
| Dirty tracking | Pre-allocated `boolean[]`. Set/cleared in place. Never resized. |
| Node traversal | Array index walk over `topoOrder[]`. No iterator, no `forEach`. |
| Child propagation | CSR flat array scan. `childrenList[start..end]`. No list creation. |
| `DoubleSourceNode` | Stores current/previous as primitive `double` fields. No boxing. |
| `DoubleNode.compute()` | Returns primitive `double`. No boxing. |
| `VectorNode` | Pre-allocated `double[]` output and previous arrays. `System.arraycopy` for save. |
| `CalcDoubleNode` lambda | Lambda allocated once at build time. Captured refs are final. JIT inlines the chain. |
| `computeN` scratch | `double[]` scratch array allocated at build time, reused every stabilization. |
| `MapNode` | Parallel `String[]`/`double[]` arrays. `keyIndex` HashMap read-only after build. |
| `BooleanNode` | Primitive `boolean` fields. No boxing. |

### 3.2 Lambda GC Impact

The lambdas are allocated once at graph build time. They capture upstream node references as final fields baked into the lambda class instance. During stabilization, calling the lambda is a virtual method call (`invokedynamic` resolved once by `LambdaMetafactory`). The JIT compiler will typically inline the entire chain: `CalcDoubleNode.compute()` → lambda → user's arithmetic.

> **Watch Out: Accidental Allocation In Your Lambdas**
>
> The framework is zero-alloc. Your code might not be. These patterns create garbage:
>
> - `String key = "tenor_" + i;` — string concat allocates
> - `Double.valueOf(x)` — autoboxing allocates
> - `new double[]{a, b, c}` — array literal allocates
> - `list.stream().mapToDouble(...)` — stream pipeline allocates
>
> **Do this instead:**
>
> - Use primitive `double` everywhere
> - Use pre-allocated scratch arrays from `g.scratchDoubles(n)`
> - Use `MapNode.putByOrdinal(i, v)` instead of `put("key", v)` if perf-critical
> - Access vector elements via `.valueAt(i)`, not `.value()[i]`

### 3.3 Cutoff Semantics

Cutoffs stop unnecessary downstream recomputation. When a node recomputes but its output has not meaningfully changed, the engine does not propagate to children. This is how you avoid re-pricing 500 swaps when a curve tenor moves by 1e-16.

| Cutoff | When To Use | Code |
|--------|-------------|------|
| `EXACT` | Default. Bitwise `doubleToRawLongBits` comparison. Propagate on any change. NaN-safe. | `DoubleCutoffs.EXACT` |
| `ALWAYS` | Always propagate. For debugging or force-refresh. | `DoubleCutoffs.ALWAYS` |
| `NEVER` | Never propagate. Freeze a node. | `DoubleCutoffs.NEVER` |
| `absoluteTolerance(t)` | Propagate if `|new - old| > t`. Good for NPV, PnL. | `DoubleCutoffs.absoluteTolerance(0.01)` |
| `relativeTolerance(t)` | Propagate if `|new-old|/max(|new|,|old|) > t`. Good for rates. | `DoubleCutoffs.relativeTolerance(1e-10)` |

`VectorNode` uses element-wise absolute tolerance. If any element changes beyond tolerance, the entire vector is considered changed. `MapNode` works the same way.

### 3.4 NaN-Safe Initial Stabilization

`DoubleNode.previousValue` starts as `Double.NaN`. On the first stabilization, the NaN check forces propagation regardless of the computed value. This prevents the bug where a node computing 0.0 from initial sources silently fails to propagate because `previousValue` was also 0.0 (Java default).

### 3.5 Why Not Structure-of-Arrays (SoA)?

A common HFT optimization question. The answer: wrong tradeoff for this problem.

- **Graph size:** Rates desk graphs are 10–200 nodes. The entire `Node<?>[]` array plus all node objects fit in L2 cache. Cache miss pressure is negligible at these scales.
- **API degradation:** SoA would turn `mid.doubleValue()` into `values[midIdx]`, requiring quants to manage integer indices instead of typed references. The builder API exists specifically to avoid this.
- **Heterogeneous types:** We have `DoubleNode`, `VectorNode`, `MapNode`, `BooleanNode`. SoA works for homogeneous flat arrays. Mixed types require separate arrays, type tags, and manual dispatch — worse than virtual calls.
- **Where SoA wins:** Monte Carlo engines running 10k identical paths. That's a different system (vectorized valuation), not an incremental dependency graph.

### 3.6 Benchmark Results

| Graph | Nodes | Avg Latency | Throughput |
|-------|-------|-------------|------------|
| bid/ask/mid/spread (minimal) | 4 | ~50ns | ~20M stab/sec |
| 9-node quoter with signal | 9 | 93ns | 10.7M stab/sec |
| 20-node quoter (realistic) | 20 | 237ns | 4.2M stab/sec |

These are single-source-tick incremental stabilizations (the common case). Full-graph re-stabilization is proportionally slower. Measured on OpenJDK 21 after JIT warmup.

---

## 4. How To Use It

### 4.1 Quick Start

Drop `CoreGraph.java` into your project. No dependencies. Import what you need:

```java
import com.trading.drg.CoreGraph.*;

var g = CoreGraph.builder("my_quoter");

// 1. Declare sources (market data)
var bid = g.doubleSource("bid", 99.5);
var ask = g.doubleSource("ask", 100.5);

// 2. Declare computations (lambdas)
var mid    = g.compute("mid",    (b, a) -> (b + a) / 2.0, bid, ask);
var spread = g.compute("spread", (a, b) -> a - b,         ask, bid);

// 3. Build (freezes the graph)
var engine = g.build();

// 4. Initial stabilization
engine.markDirty("bid");
engine.markDirty("ask");
engine.stabilize();

System.out.println(mid.doubleValue());    // 100.0
System.out.println(spread.doubleValue()); // 1.0

// 5. Tick: update source, mark dirty, stabilize
bid.updateDouble(99.0);
engine.markDirty("bid");
engine.stabilize(); // only recomputes bid, mid, spread
```

### 4.2 Compute Signatures

The builder provides typed overloads for 1, 2, 3, and N input computations. All use primitive `double`, no boxing.

| Method | Signature | Example |
|--------|-----------|---------|
| `compute` (1-in) | `Fn1: (double) → double` | `g.compute("log_p", Math::log, price)` |
| `compute` (2-in) | `Fn2: (double, double) → double` | `g.compute("mid", (b,a) -> (b+a)/2, bid, ask)` |
| `compute` (3-in) | `Fn3: (double, double, double) → double` | `g.compute("q", (t,s,k) -> t-s/2+k, theo, sp, skew)` |
| `computeN` | `FnN: (double[]) → double` | `g.computeN("sum", inputs, d -> d[0]+d[1]+d[2])` |

The `computeN` variant pre-allocates a scratch `double[]` at build time and fills it from the `DoubleReadable` inputs before calling your lambda. Zero alloc.

### 4.3 Custom Cutoffs

Pass a cutoff as the second argument to any compute method:

```java
var npv = g.compute("npv",
    DoubleCutoffs.absoluteTolerance(0.01),  // don't propagate for < 1 cent
    (r, n) -> computeSwapNpv(r, n),
    rate, notional);
```

### 4.4 Vector Nodes (Curves, Surfaces)

For computations that produce a fixed-size `double[]`:

```java
var rates = g.vectorSource("rates", 30);  // 30-tenor input
double[] tenors = { 0.25, 0.5, 1.0, /* ... */ };

var curve = g.computeVector("disc_curve", 30, 1e-12,
    new Node[]{rates},
    (inputs, output) -> {
        var r = (VectorSourceNode) inputs[0];
        for (int i = 0; i < 30; i++)
            output[i] = 1.0 / (1.0 + r.valueAt(i) * tenors[i]);
    });

// Extract single tenor as a DoubleReadable
var df_5y = g.vectorElement("df_5y", curve, 10);
```

The output array is pre-allocated. Your lambda writes into it. `VectorNode` uses element-wise absolute tolerance for the cutoff.

### 4.5 MapNode (Named Risk Buckets, Greeks)

When you need named outputs rather than a flat array:

```java
var greeks = g.mapNode("greeks",
    new String[]{"delta", "gamma", "vega", "theta", "rho"},
    new Node[]{price, vol, rate},
    (inputs, out) -> {
        double p = ((DoubleSourceNode) inputs[0]).doubleValue();
        double v = ((DoubleSourceNode) inputs[1]).doubleValue();
        out.put("delta", bsm.delta(p, v));
        out.put("gamma", bsm.gamma(p, v));
        out.put("vega",  bsm.vega(p, v));
        // ...
    });

// Read after stabilization:
double delta = greeks.get("delta");      // O(1) HashMap lookup
double gamma = greeks.getByOrdinal(1);   // O(1) array index
```

### 4.6 Signals and Conditional Logic

For quoter circuit breakers, regime detection, and conditional routing:

```java
// Boolean signal: flips only when value changes
var tooWide = g.condition("too_wide", spread, s -> s > 0.10);
var lowVol  = g.condition("low_vol",  vol,    v -> v < 0.20);

// Conditional value selection
var activeQuote = g.select("quote", lowVol, tightQuote, wideQuote);
// If lowVol is true → tightQuote, else wideQuote
```

`BooleanNode` only propagates when the boolean actually flips (true→false or vice versa). If vol ticks from 0.18 to 0.19, `lowVol` stays `true` and nothing downstream recomputes.

### 4.7 Templates (Instrument Subgraphs)

Stamp out identical subgraphs for multiple instruments without copy-paste:

```java
record SwapConfig(double notional, double fixedRate) {}
record SwapOutputs(CalcDoubleNode npv, CalcDoubleNode dv01) {}

TemplateFactory swapTemplate = (b, prefix, cfg) -> {
    var npv = b.compute(prefix + ".npv",
        (r, v) -> cfg.notional() * (r - cfg.fixedRate()) / (1 + r),
        rate, vol);
    var dv01 = b.compute(prefix + ".dv01",
        r -> cfg.notional() / ((1 + r) * (1 + r)),
        rate);
    return new SwapOutputs(npv, dv01);
};

var swap5y  = g.template("swap_5y",  swapTemplate, new SwapConfig(10e6, 0.045));
var swap10y = g.template("swap_10y", swapTemplate, new SwapConfig(5e6,  0.050));
var swap2y  = g.template("swap_2y",  swapTemplate, new SwapConfig(20e6, 0.040));

var totalNpv = g.computeN("total_npv",
    new DoubleReadable[]{swap5y.npv(), swap10y.npv(), swap2y.npv()},
    d -> d[0] + d[1] + d[2]);
```

Each template call stamps nodes with the prefix (e.g. `swap_5y.npv`, `swap_10y.dv01`). The graph builder handles all wiring automatically.

### 4.8 Scratch Space

For compute-heavy nodes that need workspace (Newton iteration, matrix decomposition):

```java
double[] workspace = g.scratchDoubles(100);
double[][] matrix  = g.scratchDoubles2D(30, 30);

var curve = g.computeVector("bootstrap_curve", 30, 1e-12,
    new Node[]{rawRates},
    (inputs, output) -> {
        bootstrap(inputs, output, workspace, matrix);  // uses pre-allocated space
    });
```

Scratch arrays are allocated once at build time. Your lambda captures them by reference. No allocation during stabilization.

---

## 5. Disruptor Integration

`GraphPublisher` is designed to sit as an EventHandler on your Disruptor ring buffer.

```java
// In your Disruptor setup:
var engine = g.build();
var publisher = new GraphPublisher(engine);

publisher.setPostStabilizationCallback((epoch, nodesRecomputed) -> {
    // Publish updated quotes, log, checkpoint, etc.
    publishQuotes(quoteAsk.doubleValue(), quoteBid.doubleValue());
});

// Wire to your EventHandler:
public void onEvent(MyEvent event, long seq, boolean endOfBatch) {
    var ge = graphEvent;  // reuse pre-allocated GraphEvent
    ge.setDoubleUpdate(event.nodeName(), event.value(),
        event.isLastInBatch(), event.seqId());
    publisher.onEvent(ge, seq, endOfBatch);
}
```

### Batching

Multiple source updates can be batched before stabilization. The `GraphPublisher` stabilizes when it sees `batchEnd=true` on the `GraphEvent` OR `endOfBatch=true` from the Disruptor. This means a full curve tick (20 tenor updates) triggers only one stabilization pass, not 20.

---

## 6. Observability and Debugging

### 6.1 GraphExplain

```java
var explain = new GraphExplain(engine);

// Dump full topology
System.out.println(explain.dumpTopology());
// Graph (8 nodes):
//   [0] usd_3m_rate (SRC) → usd_discount_curve
//   [1] usd_6m_rate (SRC) → usd_discount_curve
//   ...

// Explain a specific node
System.out.println(explain.explainNode("swap_npv"));
// Node: swap_npv
//   Topo index: 5
//   Type: CalcDoubleNode
//   Current value: 49100.07
//   Previous: 48500.12

// Last stabilization summary
System.out.println(explain.explainLastStabilization());
// Epoch: 1042, Recomputed: 5/20
```

### 6.2 Latency Tracking

```java
var lat = new LatencyTrackingListener();
engine.setListener(lat);

// After running:
System.out.printf("Avg: %.3fμs, Min: %dns, Max: %dns%n",
    lat.avgLatencyMicros(), lat.minLatencyNanos(), lat.maxLatencyNanos());

lat.reset();  // Reset for rolling windows
```

---

## 7. Snapshot and Replay

For incident response and auditability. Capture the state of all source nodes, restore later, re-stabilize to reproduce exact outputs.

```java
var ctx = g.buildWithContext();
var snapshot = new GraphSnapshot(ctx.engine().topology());

// Capture current state
snapshot.capture(ctx.engine());
byte[] bytes = snapshot.exportBytes();  // persist to disk/DB

// Later: restore and replay
snapshot.restore(ctx.engine());
ctx.engine().stabilize();  // deterministically reproduces same outputs
```

Only source nodes that implement `Snapshotable` are captured. Computed nodes are deterministic functions of their inputs, so they reproduce the same outputs after re-stabilization. This keeps snapshots small.

---

## 8. JSON Graph Compilation (Optional)

For config-driven graphs where the topology is defined externally (e.g., loaded from a config service at startup):

```json
{
  "graph": {
    "name": "rates_pricing", "version": "1.0.0",
    "nodes": [
      { "name": "usd_3m", "type": "double_source", "source": true,
        "properties": { "initial_value": 0.045, "cutoff": "exact" } },
      { "name": "curve", "type": "my_curve",
        "dependencies": ["usd_3m", "usd_6m", "usd_1y"],
        "properties": { "size": 30, "tolerance": 1e-12 } }
    ]
  }
}
```

```java
var def = JsonParser.parse(jsonString);
var compiled = new JsonGraphCompiler()
    .registerBuiltIns()                       // double_source, vector_source
    .registerFactory("my_curve", myFactory)    // your custom types
    .compile(def);

var engine = compiled.engine();
```

The JSON path and the builder API can coexist. Use JSON for production config-driven graphs. Use the builder API for prototyping and testing.

---

## 9. API Quick Reference

| Method | Returns | Description |
|--------|---------|-------------|
| `CoreGraph.builder(name)` | `GraphBuilder` | Create a new graph builder |
| `g.doubleSource(name, init)` | `DoubleSourceNode` | Scalar source (market data, param) |
| `g.doubleSource(name, init, cutoff)` | `DoubleSourceNode` | Scalar source with custom cutoff |
| `g.vectorSource(name, size)` | `VectorSourceNode` | Fixed-size array source (curve inputs) |
| `g.compute(name, fn, in1)` | `CalcDoubleNode` | 1-input double computation |
| `g.compute(name, fn, in1, in2)` | `CalcDoubleNode` | 2-input double computation |
| `g.compute(name, fn, in1, in2, in3)` | `CalcDoubleNode` | 3-input double computation |
| `g.computeN(name, inputs[], fn)` | `CalcDoubleNode` | N-input double computation |
| `g.compute(name, cutoff, fn, ...)` | `CalcDoubleNode` | Any of above with custom cutoff |
| `g.computeVector(name, size, tol, ins, fn)` | `CalcVectorNode` | Fixed-size array computation |
| `g.vectorElement(name, vec, idx)` | `CalcDoubleNode` | Extract one element from vector |
| `g.mapNode(name, keys, ins, fn)` | `MapNode` | Named key→value computation |
| `g.condition(name, input, pred)` | `BooleanNode` | Boolean signal |
| `g.select(name, cond, ifTrue, ifFalse)` | `CalcDoubleNode` | Conditional value selection |
| `g.template(prefix, factory, config)` | `T` | Stamp out subgraph from template |
| `g.scratchDoubles(n)` | `double[]` | Pre-allocate workspace array |
| `g.build()` | `StabilizationEngine` | Freeze graph, produce engine |
| `g.buildWithContext()` | `GraphContext` | Freeze graph with node-by-name access |
| `engine.markDirty(name)` | `void` | Mark node as needing recomputation |
| `engine.stabilize()` | `int` | Run one pass. Returns nodes recomputed |
| `engine.epoch()` | `long` | Stabilization count |
| `node.doubleValue()` | `double` | Read scalar output (primitive, no boxing) |
| `vec.valueAt(i)` | `double` | Read vector element |
| `map.get(key)` | `double` | Read map value by key |
| `bool.booleanValue()` | `boolean` | Read boolean signal |

---

## 10. Rules and Constraints

> **Do Not Violate These Invariants**
>
> 1. **NEVER** allocate in your compute lambdas. No `new`, no string concat, no `stream()`, no boxing.
> 2. **NEVER** read a node's value before it has been stabilized. Always stabilize first.
> 3. **NEVER** modify the graph after `build()`. The topology is frozen.
> 4. **NEVER** call `stabilize()` from multiple threads. Single-threaded only.
> 5. **NEVER** create cycles. The builder detects them and throws `IllegalStateException`.
> 6. **NEVER** call `node.stabilize()` directly. Only the engine does this.
> 7. **ALWAYS** mark source nodes dirty before calling `engine.stabilize()`.
> 8. **ALWAYS** use primitive `doubleValue()` / `valueAt()` on the hot path, not `value()`.

---

*Questions? Reach out in #rates-tech on Slack.*

