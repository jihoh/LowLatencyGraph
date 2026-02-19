# CoreGraph Developer Guide

## 1. Introduction

**CoreGraph** is a specialized, high-performance graph engine designed for **low-latency electronic trading**. It allows you to model complex pricing, risk, and signal logic as a **Directed Acyclic Graph (DAG)**.

Unlike traditional object-oriented systems where business logic is scattered across many classes and callback handlers, CoreGraph centralizes your logic into a single, deterministic, and easily testable structure defined by data.

### Key Capabilities

*   **Ultra-Low Latency:** Optimized for ~1 microsecond stabilization latency on modern CPUs.
*   **Predictability:** **Zero-GC** (Garbage Collection) on the hot path. All memory is pre-allocated.
*   **Throughput:** Capable of processing millions of market data updates per second via LMAX Disruptor batching.
*   **Wait-Free Consumption:** A Triple-Buffered snapshot mechanism allows the main application thread to read consistent data without ever blocking the graph engine.
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

The system is composed of three distinct threading domains, bridged by lock-free data structures.

```

     [LMAX RingBuffer]  <-- The Bridge: Handling Backpressure & Batching
             │
             │ (1) GraphEvent (Mutable Flyweight)
             ▼
    [Graph Processor]
             │ 
             │ (2) GraphPublisher calls stabilization
             ▼
   [StabilizationEngine]
             │
             │ (3) Walk Topological Order (int[] array scan)
             │ (4) If (Node is Dirty):
             │         Recompute();
             │         Detect Change;
             │         Mark Children Dirty;
             ▼
 [Post-Stabilization Callback]
             │
             │ (5) AsyncGraphSnapshot.update() (Atomic Triple-Buffer Swap)
             ▼
    [Main Application]
             │
             │ (6) reader.refresh()
             │ (7) Send Orders / Update UI
```

### 2.2 Core Components

| Component | Responsibility | Optimization Strategy |
|-----------|----------------|-----------------------|
| `CoreGraph` | **Façade**. The main entry point for the application. | Manages the lifecycle of the Engine, Disruptor, and Snapshotting, simplifying initialization. |
| `StabilizationEngine` | **Runtime Kernel**. Executes the graph logic. | **Zero-Allocation**. Uses `double[]` for values and flat `int[]` arrays for connectivity. Eliminates pointer chasing. |
| `GraphBuilder` | **Compiler**. Defines the topology. | Compiles the graph description into **CSR (Compressed Sparse Row)** format - flattened integer arrays for maximum cache locality. |
| `AsyncGraphSnapshot` | **Bridge**. Safe data access. | **Triple Buffering**. Ensures the writer never blocks waiting for a reader, and the reader never retries. |
| `JsonGraphCompiler` | **Loader**. JSON -> Graph. | Allows defining topology in data, decoupling strategy configuration from code compilation. |

### 2.3 The "Epoch" Concept
Every time `stabilize()` is called, the engine increments an internal `epoch` counter.
*   This creates a distinct **Logical Time** step.
*   All nodes computed in Epoch `N` see a consistent snapshot of inputs from Epoch `N-1` or `N`.
*   This guarantees **Atomic Consistency**: You typically never see "half a curve update" or "half an arb". If an input changes, all dependent nodes are updated before any output is visible.

### 2.4 Memory Layout (Structure of Arrays)
To keep the CPU cache hot, CoreGraph avoids objects for graph nodes.
Instead of `Node` objects, the engine uses **Parallel Arrays**:

```
int[] nodeType;   // { COMPUTE, SOURCE, COMPUTE ... }
double[] values;  // { 1.05,    145.2,  157.0 ... }
long[] epochs;    // { 101,     101,    101 ... }
int[] dirtyBits;  // { 0,       1,      0 ... }
```

When the engine walks the graph, it streams through these primitive arrays. This is significantly faster than chasing pointers in a traditional object graph.

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

Here is how you wire it all together in Java. This single `main` method demonstrates the full lifecycle: Initialization, Source Optimization, Publishing, and Wait-Free Consumption.

```java
public class CoreGraphDemo {
    public static void main(String[] args) throws InterruptedException {
        // --------------------------------------------------------------------
        // Step 1: Initialization
        // --------------------------------------------------------------------
        // Initialize CoreGraph with the JSON definition.
        // The constructor parses, builds, and optimizes the graph.
        CoreGraph graph = new CoreGraph("src/main/resources/tri_arb.json");

        // Start the Engine (spins up the background LMAX Disruptor thread)
        graph.start();

        // --------------------------------------------------------------------
        // Step 2: Optimizing Sources (Cold Path)
        // --------------------------------------------------------------------
        // Resolve IDs once at startup to avoid String hashing on the hot path.
        int eurUsdId = graph.getNodeId("EURUSD");
        int usdJpyId = graph.getNodeId("USDJPY");
        int eurJpyId = graph.getNodeId("EURJPY");
        
        // --------------------------------------------------------------------
        // Step 3: Simulation Loop (Hot Path - Publisher)
        // --------------------------------------------------------------------
        Thread publisher = new Thread(() -> {
            Random rng = new Random();
            double shock = 0.0;
            
            while (!Thread.currentThread().isInterrupted()) {
                // Simulate generic market moves
                shock = (rng.nextDouble() - 0.5) * 0.001;
                
                // Thread-Safe, Non-Blocking publish to LMAX RingBuffer.
                // These updates are automatically batched by the consumer.
                graph.publish(eurUsdId, 1.0850 + shock);
                graph.publish(usdJpyId, 145.20 + shock * 100);
                
                try { Thread.sleep(1); } catch (InterruptedException e) { break; }
            }
        });
        publisher.start();

        // --------------------------------------------------------------------
        // Step 4: Consuming Results (Wait-Free Reads)
        // --------------------------------------------------------------------
        // Create a reader (watches all scalar nodes by default)
        var reader = graph.getSnapshot().createReader();

        // Main Application Loop
        while (true) {
            // 1. Refresh Snapshot
            //    Atomic pointer swap (Triple Buffering). 
            //    Wait-Free: Guaranteed immediate success. Zero locks.
            reader.refresh();

            // 2. Read Consistent Values form the snapshot
            double spread = reader.get("Arb.Spread");
            double ewma   = reader.get("Arb.Spread.Ewma");

            // 3. Act on Logic
            if (Math.abs(spread) > 10.0) {
                System.out.printf("[Signal] Arb Opportunity! Spread: %.4f | EWMA: %.4f%n", spread, ewma);
            }
            
            Thread.sleep(100); // Poll at your own pace
        }
    }
}
```

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

## 6. Advanced Mechanics & Performance Tuning

### 6.1 Triple Buffering Deep Dive

**The Three Buffers:**
1.  **Dirty Buffer (Writer-local):** The Graph Thread writes new updates here. It is private to the writer.
2.  **Clean Buffer (Shared Atomic):** The latest *complete* snapshot. This acts as the hand-off point.
3.  **Snapshot Buffer (Reader-local):** The snapshot the reader is currently engaging with.

**The Swap Protocol:**
*   **On Publish (Writer):**
    1.  Write to `Dirty Buffer`.
    2.  Atomically swap `Dirty` and `Clean`. The old `Clean` becomes the new `Dirty` (recycled).
*   **On Refresh (Reader):**
    1.  Atomically swap `Snapshot` and `Clean`. The old `Snapshot` becomes the new `Clean` (recycled).

**Why it matters:**
*   **No Locks:** Neither thread ever waits on a `synchronized` block or `ReentrantLock`.
*   **Consistency:** The reader always sees a full, valid snapshot of the *entire* graph at a specific epoch of time.

### 6.2 Profiling Best Practices

Use **Java Flight Recorder (JFR)** to verify the "Zero Allocation" promise.

1.  Start your application with JFR enabled.
2.  Record for 60 seconds during peak load.
3.  Open in Java Mission Control.
4.  Inspect **Method Profiling** -> `StabilizationEngine.stabilize()`.
5.  **Success Criteria:**
    *   **TLAB Allocations:** 0 bytes.
    *   **Boxed Integers/Doubles:** None.
    *   **String Concatenation:** None.


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