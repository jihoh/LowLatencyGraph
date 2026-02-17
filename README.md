# CoreGraph Developer Manual

## 1. Introduction

**CoreGraph** is a specialized, high-performance graph engine designed for **low-latency electronic trading**. It allows you to model complex pricing, risk, and signal logic as a **Directed Acyclic Graph (DAG)**.

### Key Capabilities
*   **Speed:** ~1 microsecond stabilization latency for typical subgraphs.
*   **Throughput:** ~1 million updates/second
*   **Predictability:** Zero Garbage Collection (GC) during runtime.
*   **Safety:** Statically typed, cycle-free topology with explicit ownership.

---

## 2. Architecture

Understanding the internal data flow is critical for writing efficient graph logic.

### 2.1 Data Flow

```
   [Graph Consumer Thread]
             │
             ▼
[GraphPublisher (EventHandler)]
             │
             │ (1) Read Event (int nodeId, double val)
             │ (2) Update Primitive Source Array
             │ (3) Mark 'dirty' bit for Node ID
             ▼
   [StabilizationEngine]
             │
             │ (4) Walk Topological Order
             │ (5) If (dirty || parentChanged):
             │         Compute();
             │         Compare vs Previous;
             │         If (Changed) Mark Children Dirty;
             ▼
[Post-Stabilization Callback]
 (Safe place to read outputs)
```

### 2.2 Core Components

| Component | Role | Optimization |
|-----------|------|--------------|
| `GraphBuilder` | **Compiler**. Defines topology and validates cycles. | Compiles graph to **CSR (Compressed Sparse Row)** format - flattened integer arrays for cache locality. |
| `StabilizationEngine` | **Runtime**. Executes the graph. | **Zero-Allocation**. Uses `double[]` for state and `int[]` for connectivity. No object iterators. |
| `GraphEvent` | **Transport**. Moves data from Network -> Graph. | **Mutable Flyweight**. Lives in the RingBuffer to avoid allocation per message. |
| `GraphPublisher` | **Bridge**. Connects Disruptor to Engine. | **Batching**. If the consumer falls behind, it updates *all* pending events before stabilizing *once*. |

### 2.3 The "Epoch" Concept
Every time `stabilize()` is called, the engine increments an internal `epoch` counter.
*   This creates a **Logical Time** step.
*   All nodes computed in Epoch `N` see a consistent snapshot of inputs from Epoch `N-1` or `N`.
*   This guarantees **Atomic Consistency**: You never see "half a curve update".

---

## 3. Core Concepts

### 3.1 The Push-Wait-Stabilize Pattern
CoreGraph is **Lazy**. Setting a value does *not* trigger computation immediately.

1.  **Push:** Update one or more source nodes.
2.  **Wait:** (Optional) Update more sources. The engine just marks bits dirty.
3.  **Stabilize:** Call `engine.stabilize()`. The engine waves the changes through the graph.

### 3.2 Primitive First
*   We use `double` and `double[]` everywhere.
*   **Boxing (`Double`) is banned** on the hot path.
*   **String Lookups are banned** on the hot path. Use cached `int` IDs.

---

## 4. Quant Cookbook: 12 Real-World Examples

All examples use `GraphBuilder g`.

### Example 1: Mid Price & Spread (Scalar)
The "Hello World" of trading.

```java
// Sources
var bid = g.scalarSource("mkt.bid", 100.0);
var ask = g.scalarSource("mkt.ask", 100.5);

// Logic: Mid = (Bid + Ask) / 2
var mid = g.compute("calc.mid", (b, a) -> (b + a) / 2.0, bid, ask);

// Logic: Spread = Ask - Bid
var spread = g.compute("calc.spread", (a, b) -> a - b, ask, bid);
```

### Example 2: FX Cross Rate (Triangular Arb)
Deriving `EUR/JPY` from `EUR/USD` and `USD/JPY`.

```java
var eurUsd = g.scalarSource("mkt.eur_usd", 1.08);
var usdJpy = g.scalarSource("mkt.usd_jpy", 150.00);

// Cross: EUR/JPY = EUR/USD * USD/JPY
var eurJpy = g.compute("calc.eur_jpy", 
    (eu, uj) -> eu * uj, 
    eurUsd, usdJpy
);
```

### Example 3: Order Book Imbalance (Signal)
A strictly normalized signal (-1 to +1) based on liquidity.

```java
var bidQ = g.scalarSource("mkt.bid_qty", 1000);
var askQ = g.scalarSource("mkt.ask_qty", 5000);

// Imbalance = (BidQ - AskQ) / (BidQ + AskQ)
var imbalance = g.compute("sig.imbalance", 
    (bq, aq) -> {
        double sum = bq + aq;
        return (sum == 0) ? 0.0 : (bq - aq) / sum;
    }, 
    bidQ, askQ
);
```

### Example 4: VWAP Snapshot (Weighted Average)
Calculating the Volume Weighted Average Price of the last N trades.
*Note: Real VWAP requires state (history). This is a snapshot version.*

```java
var p1 = g.scalarSource("trade.p1", 100.50); var v1 = g.scalarSource("trade.v1", 100);
var p2 = g.scalarSource("trade.p2", 100.55); var v2 = g.scalarSource("trade.v2", 200);

// VWAP = Sum(P*V) / Sum(V)
// Use computeN for array inputs
var vwap = g.computeN("calc.vwap",
    (inputs) -> {
        double sumPv = 0, sumV = 0;
        for(int i=0; i<inputs.length; i+=2) {
            double p = inputs[i];
            double v = inputs[i+1];
            sumPv += p * v;
            sumV += v;
        }
        return (sumV == 0) ? 0 : sumPv / sumV;
    },
    p1, v1, p2, v2
);
```

### Example 5: Yield Curve Bootstrap (Vector)
Calculating Discount Factors from raw rates. `VectorNode` dominates here.

```java
int POINTS = 10;
var rates = g.vectorSource("mkt.rates", POINTS); // Input: [0.045, 0.046...]

// Output: Discount Factors df = 1 / (1 + r)^t
// Times are constant: 1Y, 2Y... 10Y
double[] times = {1,2,3,4,5,6,7,8,9,10};

var dfs = g.computeVector("calc.dfs", POINTS, 1e-9,
    new Node[]{ rates },
    (inputs, output) -> {
        VectorSourceNode rNode = (VectorSourceNode)inputs[0];
        for(int i=0; i<POINTS; i++) {
            double r = rNode.valueAt(i);
            output[i] = 1.0 / Math.pow(1.0 + r, times[i]);
        }
    }
);
```

### Example 6: Scenario Analysis (Parallel Shift)
"What is the curve if rates go up 50bps?"

```java
var baseCurve = g.vectorSource("base_curve", 10);
var shift = g.scalarSource("scenario.shift", 0.0050); // +50bps

var shockedCurve = g.computeVector("scen.up50", 10, 1e-9,
    new Node[]{ baseCurve, shift },
    (inputs, output) -> {
        VectorSourceNode curve = (VectorSourceNode)inputs[0];
        double s = ((ScalarValue)inputs[1]).doubleValue();
        
        for(int i=0; i<10; i++) {
            output[i] = curve.valueAt(i) + s;
        }
    }
);
```

### Example 7: Volatility Surface Slice (Vector Element)
Extracting a single "Smile" (Strike vs Vol) at a specific tenor from a full surface.

```java
// Assume surface is flattened: [T1_K1, T1_K2, ... T2_K1...]
var surface = g.vectorSource("mkt.vol_surface", 100); 

// We want the slice at Index 20-29 (The 1Y tenor)
var slice1Y = g.computeVector("vol.1y_slice", 10, 1e-6,
    new Node[]{ surface },
    (inputs, output) -> {
        VectorSourceNode surf = (VectorSourceNode)inputs[0];
        // Efficient array copy (Zero-GC, effectively System.arraycopy)
        for(int i=0; i<10; i++) {
            output[i] = surf.valueAt(20 + i);
        }
    }
);
```

### Example 8: Safe-Spread Logic (Circuit Breaker)
Widening the spread if Volatility spikes.

```java
var rawSpread = g.scalarSource("algo.spread", 0.02);
var vol = g.scalarSource("mkt.vol", 0.15);

// 1. Condition: Is Vol > 50%?
var highVol = g.condition("state.high_vol", vol, v -> v > 0.50);

// 2. Logic: If HighVol, 5x spread. Else 1x.
var wideSpread = g.compute("calc.wide_spread", s -> s * 5.0, rawSpread);

// 3. Selection
var finalSpread = g.select("final_spread", 
    highVol, 
    wideSpread, // True branch
    rawSpread   // False branch
);
```

### Example 9: Synthetic Instrument
Implied price of a product that doesn't trade, derived from proxies.
e.g., `SynPrice = ProxyA * 0.5 + ProxyB * 0.5 + Spread`

```java
var proxyA = g.scalarSource("mkt.proxy_a", 100);
var proxyB = g.scalarSource("mkt.proxy_b", 101);
var basis = g.scalarSource("cfg.basis", 0.50);

var synPrice = g.compute("calc.syn",
    (a, b, bases) -> (a * 0.5) + (b * 0.5) + bases,
    proxyA, proxyB, basis
);
```

### Example 10: P&L Report (MapNode)
Bundling multiple greek risks into a key-value snapshot for reporting.

```java
var npv = g.scalarSource("risk.npv", 1000);
var delta = g.scalarSource("risk.delta", 50);
var gamma = g.scalarSource("risk.gamma", 2);

// Map keys defined at build time
var report = g.mapNode("report.pnl",
    new String[]{ "NPV", "Delta", "Gamma" },
    new Node[]{ npv, delta, gamma },
    (inputs, writer) -> {
        // Fast writer (flyweight)
        writer.put("NPV",   inputs[0].doubleValue());
        writer.put("Delta", inputs[1].doubleValue());
        writer.put("Gamma", inputs[2].doubleValue());
    }
);
```

### Example 11: Mass Production with Templates (TemplateFactory)
You often need to price 1,000 swaps with identical logic but different parameters (Notional, Fixed Rate).
Use the `TemplateFactory` pattern.

```java
// 1. Define Config Record
record SwapConfig(double notional, double fixedRate) {}
// 2. Define Output Record (Holds the Nodes)
record SwapOutputs(Node<?> npv, Node<?> dv01) {}

// 3. Define the Template Logic
TemplateFactory<SwapConfig, SwapOutputs> swapTemplate = (b, prefix, cfg) -> {
    // Inputs (could be looked up or passed in)
    var curve = b.vectorSource("mkt.curve", 10);
    
    var npv = b.compute(prefix + ".NPV", 
        (r) -> cfg.notional * (r[0] - cfg.fixedRate), // Simplified logic
        curve
    );
    
    var dv01 = b.compute(prefix + ".DV01", ...);
    
    return new SwapOutputs(npv, dv01);
};

// 4. Instantiate 1000 Swaps
for (int i=0; i<1000; i++) {
    var cfg = new SwapConfig(1_000_000, 0.05);
    // "Stamp" the logic into the graph
    var swap = g.template("Swap" + i, swapTemplate, cfg);
    
    // Wire swap.npv() to a report...
}
```

### Example 12: Stateful Logic (EWMA)
Sometimes you need history (e.g., Moving Average).
The graph is stateless by default, but you can capture state in the closure.

**The Closure Trick:**
Use a 1-element array to hold state inside the lambda. The lambda is instantiated **once** at build time, so the array persists.

```java
var price = g.scalarSource("mkt.px", 100.0);

// Capture state in closure
final double[] state = new double[1]; // [0] = lastEMA
final boolean[] init = { false };     // Is initialized?

var ewma = g.compute("sig.ewma", (px) -> {
    if (!init[0]) {
        state[0] = px; // Init with first price
        init[0] = true;
        return px;
    }
    
    double alpha = 0.1;
    double prev = state[0];
    double next = alpha * px + (1.0 - alpha) * prev;
    
    // Update state for next cycle
    state[0] = next;
    return next;
}, price);
```

### Example 13: Boolean Logic (Signals)
CoreGraph supports boolean signals for logic gates and valid/invalid flags.

```java
var mid = g.scalarSource("mkt.mid", 100.0);

// 1. Create Boolean Signal (Condition)
// Returns a BooleanNode that is true/false based on the predicate
var isValid = g.condition("sig.valid", mid, m -> m > 0 && !Double.isNaN(m));

// 2. Use it in downstream logic
// e.g. "SafePrice" = Mid if Valid, else NaN
var safePrice = g.compute("calc.safe_price", 
    (m, valid) -> valid ? m : Double.NaN,
    mid, isValid
);

// 3. Complex Boolean Logic
// You can combine booleans using generic compute
var isBigRequest = g.condition("sig.big", ...);
var isTradeable = g.compute("sig.tradeable", (v, b) -> v && !b, isValid, isBigRequest);
```

---

## 5. Best Practices

### 5.1 Granularity: "Business Units" vs. "AST"

**The "Graph Tax"**: Every node introduces overhead (dirty check, recursion, cache fixup).
*   **Do Not**: Create a node for every `+` or `-` operator. (AST style).
*   **Do**: Bundle coherent equations into a single `compute` node. (Business Logic style).

**Example: Euclidean Distance** `sqrt(x^2 + y^2)`
*   **Bad**: `X` -> `SqrX`, `Y` -> `SqrY`, `Sum` -> `Sqrt`. (5 nodes).
*   **Good**: `X,Y` -> `Calc` (where `Calc = Math.sqrt(x*x + y*y)`). (1 node). The JIT compiler optimizes the internals of the node into efficient machine instructions (SQRTSD).

### 5.2 VectorNode vs. ScalarNodes

**Question:** "Why not just use 100 `ScalarNode`s for a Yield Curve?"

**Answer: CPU Architecture.**
1.  **Cache Locality:** `VectorNode` uses a contiguous `double[]`. The CPU pulls in 64 bytes (8 doubles) in a single cycle. With scattered objects, you get cache misses on every access.
2.  **Engine Overhead:** Processing 1 node is 100x cheaper than processing 100 nodes.
3.  **Consistency:** Vectors update atomically. There is no risk of a "teared" state where half the curve is old and half is new.

**Rule:**
*   Distinct Values (`Spot`, `VIX`) -> `ScalarNode`.
*   Homogeneous Data (`YieldCurve`, `VolSurface`) -> `VectorNode`.

### 5.3 Zero-Allocation Rules
To maintain the "Zero-GC" promise:

1.  **No `new` in Lambdas**: Never allocate objects inside a `compute` lambda.
2.  **Use Scratch Buffers**: Use `GraphBuilder.scratchDoubles()` or pass pre-allocated arrays to your functions.
3.  **No Strings**: Do not perform String concatenation or parsing on the hot path.

### 5.4 Lookup Caching
String lookups (e.g., `getNode("bid")`) are slow (HashMap access).
**Always** cache integer IDs at startup:

---

## 6. Integration Guide: Production Wiring

This section details how to integrate CoreGraph into a production trading chassis.

### 6.1 The Lifecycle

1.  **Construction (Cold Path)**: Build the graph, load configuration.
2.  **Compilation (Warm Path)**: `g.buildWithContext()`. Perform JIT warmup.
3.  **Optimization (Warm Path)**: Cache all Node IDs.
4.  **Runtime (Hot Path)**: Enter the event loop.

### 6.2 Caching Node IDs (Crucial)

**Problem:** `engine.getNodeId("Mkt.Bid")` involves hashing a String and checking a Map. This allocates memory and is O(L) where L is string length.
**Solution:** Resolve all IDs to `int` during startup. The engine uses direct array access (O(1)) for `int` IDs.

```java
// In your init() method:
GraphContext ctx = g.buildWithContext();

// Store these in fields
private final int bidId = ctx.getNodeId("Mkt.Bid");
private final int askId = ctx.getNodeId("Mkt.Ask");
```

### 6.3 The Disruptor Pattern

We recommend the **Single Producer, Single Consumer** pattern.

#### Architecture
`[Network IO] -> [RingBuffer<GraphEvent>] -> [GraphPublisher] -> [StabilizationEngine]`

#### Step 1: The Event Factory
Pre-allocate 1024 (or power of 2) events in the RingBuffer.

```java
Disruptor<GraphEvent> disruptor = new Disruptor<>(
    GraphEvent::new, 1024, DaemonThreadFactory.INSTANCE,
    ProducerType.SINGLE, new BlockingWaitStrategy()
);
```

#### Step 2: The Producer (Hot Loop)
This runs on your Netty/NIO thread. It must translate network messages to Graph IDs.
The `batchEnd` flag is critical here. It tells the consumer if it should stabilize now (true) or wait for more updates (false).

```java
public void onMarketData(int instrumentId, double price) {
    long seq = ringBuffer.next();
    try {
        GraphEvent e = ringBuffer.get(seq);
        int graphNodeId = this.nodeMap[instrumentId];
        
        // batchEnd=true means "Stabilize now". 
        // batchEnd=false means "Just update state, I have more changes coming".
        e.setDoubleUpdate(graphNodeId, price, true, seq); 
    } finally {
        ringBuffer.publish(seq);
    }
}
```

#### Step 3: The Consumer (GraphPublisher) & Batching
The `GraphPublisher` (included in library) acts as the bridge. It implements `EventHandler<GraphEvent>`.

**Smart Batching:**
The Disruptor passes a boolean `endOfBatch` to the handler. The `GraphPublisher` uses this to coalesce updates:
1.  If `endOfBatch == false`, it applies the update but **does not stabilize**.
2.  If `endOfBatch == true` OR the event's `batchEnd == true`, it **triggers stabilization**.

This means if the consumer is slow, it might process 100 updates and then run 1 graph calculation, maximizing throughput.

```java
GraphPublisher publisher = new GraphPublisher(engine);

// Register a Callback to READ outputs safely AFTER stabilization
publisher.setPostStabilizationCallback((epoch, count) -> {
    // This runs on the Graph Thread. Safe to read outputs.
    double mid = midNode.doubleValue();
    riskEngine.send(mid);
});

disruptor.handleEventsWith(publisher::onEvent);
disruptor.start();
```

### 6.4 Threading Model

CoreGraph is **Single-Threaded**.
*   **The Golden Rule:** Only ONE thread may call `engine.stabilize()` at a time.
*   **Visibility:** If you read graph outputs from another thread (e.g., UI), you must ensure memory visibility (e.g., via `volatile` reads or passing messages back to a UI queue).

**Recommendation:** Pin the Graph Thread to a specific CPU core to avoid context switching.

### 6.5 Monitoring & Observability

#### Metrics (JMX/Micrometer)
Export these counters:
1.  `stabilization_count`: Total number of stabilizations.
2.  `nodes_recomputed_per_cycle`: Average nodes touched (Work amplification).
3.  `stabilization_latency`: Histogram (P99).

#### Profiling (Java Flight Recorder)
Run JFR in production.
*   **Target:** `StabilizationEngine.stabilize()`.
*   **Success Criteria:** Zero allocation samples inside this method.
*   **Warning Signs:** `Double.valueOf`, `Iterator.next`, `String.format` appearing in the hot path.

## 7. Finance Library (Class-Based Nodes)

CoreGraph provides a library of zero-allocation, stateful financial functions in `com.trading.drg.fn.finance`.
These classes implement `Fn1` (scalar -> scalar) or `Fn2` (scalar, scalar -> scalar) and can be used directly in `compute` nodes.

### 7.1 Key Benefits
1.  **Zero GC:** State is pre-allocated (arrays) and reused.
2.  **Encapsulation:** No need to manage `double[]` arrays in lambdas manually.
3.  **Composability:** Easy to chain.

### 7.2 Available Functions

| Class | Description | Usage |
|-------|-------------|-------|
| `Ewma` | Exponential Moving Average | `new Ewma(alpha)` |
| `Sma` | Simple Moving Average (Ring Buffer) | `new Sma(windowSize)` |
| `Diff` | First Difference (`x[t] - x[t-1]`) | `new Diff()` |
| `LogReturn` | Log Return (`ln(x[t] / x[t-1])`) | `new LogReturn()` |
| `HistVol` | Historic Volatility (StdDev) | `new HistVol(windowSize)` |
| `ZScore` | Z-Score (`(x - mean) / stdDev`) | `new ZScore(windowSize)` |
| `Rsi` | Relative Strength Index (Wilder's) | `new Rsi(period)` |
| `Macd` | MACD Line (`FastEWMA - SlowEWMA`) | `new Macd(fast, slow)` |
| `RollingMax` | Rolling Maximum (Linear Scan) | `new RollingMax(windowSize)` |
| `RollingMin` | Rolling Minimum (Linear Scan) | `new RollingMin(windowSize)` |
| `Beta` | Rolling Beta (`Cov(X,Y)/Var(X)`) | `new Beta(windowSize)` |
| `Correlation` | Rolling Correlation | `new Correlation(windowSize)` |

### 7.3 Usage Example

```java
import com.trading.drg.fn.finance.*;

// 1. Define Sources
var price = g.scalarSource("mkt.mid", 100.0);

// 2. Use Class-Based Nodes
// RSI 14
var rsi = g.compute("sig.rsi", new Rsi(14), price);

// MACD (12, 26)
var macdLine = g.compute("sig.macd", new Macd(12, 26), price);

// Bollinger Bands (SMA + 2 * StdDev)
var ma     = g.compute("bb.ma",    new Sma(20), price);
var stdDev = g.compute("bb.stdev", new HistVol(20), price);

var upper = g.compute("bb.upper", (m, s) -> m + 2*s, ma, stdDev);
var lower = g.compute("bb.lower", (m, s) -> m - 2*s, ma, stdDev);
```

### 7.4 How to Implement Custom Class-Based Nodes

You can implement `Fn1`, `Fn2`, `Fn3`, or `FnN` as a class.
This is especially useful when:
1.  **Complex Logic**: The calculation is too long for a lambda.
2.  **Stateful Logic**: You need to maintain internal state (like an EWMA, PID Controller, or Rate Limiter).
3.  **Reusability**: You use the same logic in multiple places.

#### Example: Stateful EWMA as a Class

Here is how you can encapsulate the "Exponential Moving Average" logic into a clean `Fn1` implementation.

```java
import com.trading.drg.fn.Fn1;

/**
 * Stateful Exponential Moving Average.
 * Implements Fn1 so it can be used in g.compute(name, new Ewma(0.1), input).
 */
public class Ewma implements Fn1 {
    private final double alpha;
    private double state;
    private boolean initialized = false;

    public Ewma(double alpha) {
        this.alpha = alpha;
    }

    @Override
    public double apply(double input) {
        if (!initialized) {
            state = input;
            initialized = true;
            return state;
        }
        
        // Classic EWMA: New = Alpha * Input + (1 - Alpha) * Old
        state = alpha * input + (1.0 - alpha) * state;
        return state;
    }
}
```

#### Usage in GraphBuilder

Since `Ewma` implements `Fn1`, you can pass an instance directly to `g.compute`:

```java
var price = g.scalarSource("mkt.px", 100.0);

// Use the class instance instead of a lambda
var fastEwma = g.compute("sig.ewma_fast", new Ewma(0.5), price);
var slowEwma = g.compute("sig.ewma_slow", new Ewma(0.05), price);
```

#### Why this is better than closure-based state
*   **Cleaner**: No `final double[] state = new double[1]` hacks.
*   **Encapsulated**: The state is private to the class instance.

#### Example: Triangular Arbitrage (Fn3)

This class calculates the difference (spread) between a direct currency pair and a synthetic cross rate.

```java
import com.trading.drg.fn.Fn3;

public class TriangularArbSpread implements Fn3 {
    @Override
    public double apply(double leg1, double leg2, double direct) {
        // Spread = Direct - (Leg1 * Leg2)
        return direct - (leg1 * leg2);
    }
}
```

**Usage:**

```java
var eurUsd = g.scalarSource("mkt.eur_usd", 1.05);
var usdJpy = g.scalarSource("mkt.usd_jpy", 150.0);
var eurJpy = g.scalarSource("mkt.eur_jpy", 157.5);

var arbSpread = g.compute("sig.arb_spread", 
    new TriangularArbSpread(), 
    eurUsd, usdJpy, eurJpy
);
```

#### Example: Harmonic Mean (FnN)

This class calculates the Harmonic Mean of N inputs (e.g., averaging P/E ratios).
All inputs are of the same type.

```java
import com.trading.drg.fn.FnN;

public class HarmonicMean implements FnN {
    @Override
    public double apply(double[] inputs) {
        if (inputs.length == 0) return 0.0;
        
        double sumInverse = 0;
        for (double val : inputs) {
            sumInverse += 1.0 / val;
        }
        return inputs.length / sumInverse;
    }
}
```

**Usage:**

```java
var pe1 = g.scalarSource("pe.google", 25.0);
var pe2 = g.scalarSource("pe.amazon", 40.0);
var pe3 = g.scalarSource("pe.meta",   30.0);

// Calculate average P/E of the sector
var sectorPE = g.computeN("calc.sector_pe", 
    new HarmonicMean(),
    pe1, pe2, pe3
);
```

