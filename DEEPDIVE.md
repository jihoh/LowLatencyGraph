# LLGraph: A Technical Deep Dive into Sub-Microsecond Reactive Graph Stabilization

## What Is LLGraph?

LLGraph is a **reactive computation graph engine** built in Java for high-frequency trading (HFT) workloads. It models financial calculations—bid/ask spreads, rolling correlations, moving averages—as a directed acyclic graph (DAG) of interdependent nodes. When a market data tick arrives, the engine propagates the change through only the affected subgraph, recomputing derived values in strict topological order.

The headline number: **sub-microsecond stabilization** of 1,300+ node graphs, with **zero bytes allocated per tick** on the hot path.

This document dissects the seven engineering techniques that make this possible—from algorithmic design down to CPU cache lines, branch predictor behavior, and JIT compiler co-design.

---

## 1. Sparse Topological BitSet — O(K) Evaluation via Hardware Intrinsics

**The problem:** In a 1,300-node graph, a single price tick might only affect 15–30 downstream nodes. A naive full-graph sweep wastes 98% of its cycles visiting clean nodes.

**The solution:** Dirty-node tracking is encoded as a `long[]` bitset (`dirtyNodeBits`), where each bit corresponds to a node in topological order. The stabilization loop skips entire 64-node segments by checking if a `long` word is zero, and within a non-zero word, jumps directly to the next dirty bit using `Long.numberOfTrailingZeros()`—a JVM intrinsic that compiles to a single `TZCNT` or `BSF` hardware instruction on x86.

```java
// StabilizationEngine.java — the entire hot loop
for (int w = 0; w < dirtyNodeBits.length; w++) {
    while (dirtyNodeBits[w] != 0L) {
        int bitIndex = Long.numberOfTrailingZeros(dirtyNodeBits[w]); // 1 CPU cycle
        int ti = (w << 6) + bitIndex;                                // Topological index

        dirtyNodeBits[w] &= ~(1L << bitIndex);                      // Clear before eval

        Node node = topology.node(ti);
        boolean changed = node.stabilize();

        if (changed) {
            // Mark children dirty via CSR (see §2)
            for (int ci = topology.childrenStart(ti); ci < topology.childrenEnd(ti); ci++) {
                int childTi = topology.childAt(ci);
                dirtyNodeBits[childTi >> 6] |= (1L << childTi);     // O(1) bitwise set
            }
        }
    }
}
```

**Complexity:** O(K + W) where K = dirty nodes and W = bitset words scanned. For a 1,300-node graph, W = 21 longs. In practice, the outer loop short-circuits on zero words, making this effectively O(K).

**Why not `java.util.BitSet`?** The JDK `BitSet` uses internal `long[]` arrays but wraps every operation in bounds checks, synchronization, and iterator objects. By inlining the raw `long[]` directly, we eliminate all method dispatch overhead and allow the JIT to keep the array reference in a register across iterations.

### Branch Prediction in the Inner Loop

Modern CPUs execute instructions speculatively, predicting which branch a conditional will take before it is evaluated. A misprediction costs ~15–20 cycles (pipeline flush). Every branch in this loop has a strong statistical bias that the predictor learns within the first few stabilization passes:

| Branch | Typical outcome | Why |
|---|---|---|
| `dirtyNodeBits[w] != 0L` | **Usually false** (most words are zero) | Sparse graph: ~3 of 21 words are non-zero per tick |
| `ti >= n` (bounds check) | **Always false** in normal operation | Safety guard; never fires on valid graphs |
| `node.stabilize()` → `changed` | **Usually true** for dirty nodes | A node is only dirty because its parent changed |
| `node instanceof BranchingNode` | **Almost always false** | Only `SwitchNode` implements this; <1% of nodes |

After JVM warmup, the outer `for` loop over zero-words is essentially free—the CPU speculatively skips them without stalling. The `if (changed)` branch is biased `true`, so the child-marking code is speculatively executed before `stabilize()` even returns, keeping the pipeline full.

### Temporal Cache Locality of `dirtyNodeBits`

For a 1,300-node graph, `dirtyNodeBits` is only 21 `long` values = **168 bytes**, fitting entirely in **3 CPU cache lines** (64 bytes each). Because the entire stabilization pass completes in under 1µs, this array is accessed repeatedly and never has time to be evicted from L1 cache between accesses. Both the outer scan loop and the child-marking writes (`dirtyNodeBits[childTi >> 6] |= ...`) hit the same hot cache lines, achieving perfect temporal locality.

---

## 2. Compressed Sparse Row (CSR) Edge Encoding — Cache-Line Friendly Traversal

**The problem:** Traditional adjacency lists (`Map<Node, List<Node>>`) scatter child references across the heap, causing L1/L2 cache misses on every edge traversal during propagation.

**The solution:** Edges are flattened into two contiguous `int[]` arrays at graph construction time, using the Compressed Sparse Row (CSR) format:

```
childrenOffset[]:  [0, 3, 3, 5, 8, ...]   ← start index per node
childrenList[]:    [4, 7, 12, 9, 11, ...]  ← all children, packed sequentially
```

- `childrenOffset[ti]` to `childrenOffset[ti+1]` defines the slice of `childrenList` belonging to node `ti`.
- All children of a node are contiguous in memory, so iterating them triggers sequential prefetching by the CPU's hardware prefetcher.

```java
// TopologicalOrder.java
public int childrenStart(int ti) { return childrenOffset[ti]; }
public int childrenEnd(int ti)   { return childrenOffset[ti + 1]; }
public int childAt(int flatIdx)  { return childrenList[flatIdx]; }
```

### Spatial Locality: Why CSR Dominates on Modern CPUs

**Spatial locality** means that when the CPU fetches one piece of data, the adjacent data it needs next is already in the same cache line (64 bytes on x86). CSR is designed to exploit this at every access:

1. **Adjacent offset reads:** `childrenOffset[ti]` and `childrenOffset[ti+1]` are adjacent `int` values—guaranteed to be in the same cache line. One memory fetch retrieves both.

2. **Sequential child iteration:** The child-marking loop reads `childrenList[start]`, `childrenList[start+1]`, ..., `childrenList[end-1]`. This is a **linear scan of packed 4-byte integers**. For a node with 5 children, that's 20 bytes—all within a single 64-byte cache line. The CPU's hardware prefetcher recognizes this stride-1 pattern and pre-loads the next cache line before the loop even requests it.

3. **Node array access:** `Node[] topoOrder` stores node references in topological execution order. Dirty nodes within the same 64-bit word are processed sequentially (`ti` increases monotonically within a word), so the CPU prefetcher can anticipate the next node reference.

**Contrast with object-oriented adjacency:** A `HashMap<Node, List<Node>>` requires: (1) hash the `Node` pointer, (2) follow the bucket chain (pointer chase), (3) retrieve the `List` object (pointer chase), (4) access the internal `Object[]` (pointer chase), (5) cast each `Object` to `Node` (pointer chase). That's **5 pointer indirections**—each potentially a ~4ns L2 cache miss—vs. **0 indirections** for CSR. On a node with 5 children, this is the difference between ~20ns of cache misses and ~1ns of sequential reads.

---

## 3. Zero-Allocation Hot Path — Eliminating GC and Co-Designing for the JIT

**The problem:** Even a minor allocation per tick (e.g., an autoboxed `Double`, a lambda capture, an iterator) will eventually fill the young generation and trigger a Stop-the-World GC pause—typically 50–200µs, which is catastrophic when your stabilization target is <1µs.

**The techniques:**

| Technique | What it prevents |
|---|---|
| **Primitive fields** (`double currentValue`, not `Double`) | Autoboxing allocations |
| **Pre-allocated arrays** (`double[] window`, `long[] dirtyNodeBits`) | Array creation per tick |
| **`@FunctionalInterface` on primitives** (`Fn1: double → double`) | Lambda capture / boxing |
| **Circular buffers** for sliding windows | Collection resizing |
| **`System.arraycopy`** for vector state snapshots | Temporary array allocation |

```java
// ScalarNode.java — zero-allocation stabilize()
public final boolean stabilize() {
    previousValue = currentValue;     // Primitive swap, no boxing
    currentValue = compute();         // Subclass returns raw double
    return cutoff.hasChanged(previousValue, currentValue); // Primitive comparison
}
```

```java
// VectorNode.java — pre-allocated double[] reuse
public final boolean stabilize() {
    System.arraycopy(currentValues, 0, previousValues, 0, size); // In-place copy
    compute(currentValues);  // Writes directly into pre-allocated buffer
    // Element-wise comparison, no temporary objects
    for (int i = 0; i < size; i++) {
        if (Math.abs(currentValues[i] - previousValues[i]) > tolerance) return true;
    }
    return false;
}
```

**Enforcement:** A JMX-based `AllocationProfiler` wraps `ThreadMXBean.getThreadAllocatedBytes()` around each stabilization epoch. In steady state (post-JIT warmup), it reports **exactly 0 bytes** allocated on the engine thread per tick. Any regression triggers an immediate console warning:

```java
if (bytesAllocated > 0 && sequence > 10000) {
    System.err.println("WARNING: Hot-path allocated " + bytesAllocated + " bytes");
}
```

### JIT Compiler Co-Design: Writing Code the JVM Wants to Optimize

Zero-allocation is necessary but not sufficient. LLGraph's code structure is explicitly designed to allow HotSpot's C2 JIT compiler to apply its most aggressive optimizations:

**Monomorphic call sites via `final`:** The JIT can inline a virtual method call only when it observes a single concrete type at a given call site (**monomorphic**). Two or more types cause a fallback to virtual dispatch (~5ns overhead). LLGraph forces monomorphic dispatch by declaring all hot-path classes and methods `final`:

```java
public final class ScalarCalcNode extends ScalarNode { ... }   // final class
public final class VectorCalcNode extends VectorNode { ... }   // final class

// ScalarNode.java
public final boolean stabilize() { ... }  // final — cannot be overridden
public final double value() { ... }       // final — JIT inlines directly
```

**Small method bodies for inlining:** HotSpot has a bytecode size threshold (~325 bytes) above which it refuses to inline. All critical node methods are intentionally kept minimal:

```java
// VectorCalcNode.compute() — 2 lines, trivially inlinable
@Override
protected void compute(double[] output) {
    fn.compute(inputs, output);  // Single delegation, JIT inlines the fn too
}
```

The JIT can inline through the entire chain: `stabilize()` → `compute()` → `fn.apply()` → actual math (`Math.sqrt`, `a * b + c`). A 4-layer method call collapses into a single block of machine instructions with **zero call overhead**.

**Escape analysis and scalar replacement:** By using primitive `double` fields and `double[]` arrays (never `Double` or `List<Double>`), the JIT's escape analysis keeps all intermediate values in CPU registers or on the stack—never on the heap. The `ScalarCutoff` lambdas (e.g., `(p, c) -> Math.abs(c - p) > tol`) capture only a single primitive `double` (the tolerance). HotSpot recognizes these as non-escaping and replaces the lambda object with inlined scalar code after warmup.

---

## 4. LMAX Disruptor — Batched Stabilization and Single-Writer Mechanical Sympathy

**The problem:** Market data arrives in bursts (e.g., 5 correlated quote updates within a microsecond). Stabilizing the graph after every individual update wastes work—if `bid` and `ask` both update, their shared descendants are recomputed twice.

**The solution:** The LMAX Disruptor provides a lock-free ring buffer between the I/O thread (producer) and the engine thread (consumer). Its key feature is the `endOfBatch` flag: the consumer knows when it has drained all pending events and can defer stabilization to that exact moment.

```java
// CoreGraphComplexDemo.MarketDataEventHandler
public void onEvent(MarketDataEvent event, long sequence, boolean endOfBatch) {
    router.route(event);              // Apply update to graph (no stabilization)

    if (endOfBatch) {
        graph.stabilize();            // Stabilize ONCE for the entire burst
    }
}
```

**Concrete impact:** A burst of 5 updates to the same instrument only triggers 1 stabilization pass instead of 5. The dirty bitset naturally coalesces overlapping subgraphs, so even if all 5 ticks dirty different source nodes, the engine traverses the union of their descendants exactly once.

**Wait strategy:** `YieldingWaitStrategy` is used for the consumer thread—it spin-waits briefly then yields, achieving ~100ns wake-up latency without burning a full CPU core.

### Single-Writer Architecture: No Locks, No Atomics, No Memory Barriers

The Disruptor enforces a strict **single-writer** principle: exactly one thread reads and writes `dirtyNodeBits[]`, `Node[]`, and all node state. This eliminates three categories of hardware-level overhead that are invisible in high-level profilers but devastating at sub-microsecond scales:

1. **No `synchronized` or `Lock`**: Acquiring a Java monitor requires a CAS (compare-and-swap) instruction, which flushes the CPU store buffer and forces a memory fence. Cost: **~20–50ns per acquisition**—potentially more than the entire stabilization pass.
2. **No `volatile` fields**: A `volatile` read/write inserts a `LoadLoad`/`StoreStore` memory barrier, preventing the CPU from reordering instructions. This disables out-of-order execution optimizations that modern CPUs rely on for throughput.
3. **No `AtomicInteger`/`AtomicLong`**: Atomic operations use `LOCK CMPXCHG` on x86, which locks the cache line across all cores. Even uncontended, this costs **~10–20ns** due to the bus lock.

```java
// StabilizationEngine.markDirty() — plain memory write, no fence needed
public void markDirty(int topoIndex) {
    int wordIdx = topoIndex >> 6;
    long bitMask = 1L << topoIndex;
    if ((dirtyNodeBits[wordIdx] & bitMask) == 0) {
        dirtyNodeBits[wordIdx] |= bitMask;   // Plain write — no LOCK prefix
        eventsProcessed++;                     // Plain increment — no CAS
    }
}
```

### False Sharing Avoidance via MESI Protocol Awareness

**False sharing** occurs when two threads write to different variables that share the same 64-byte cache line. The CPU's cache coherency protocol (MESI) must then bounce the line between cores—each bounce costs ~40–70ns. LLGraph avoids this entirely: because only one thread touches the engine's data structures, the `dirtyNodeBits[]` array stays in the **Exclusive** MESI state in the engine thread's L1 cache—no cross-core invalidation traffic, ever.

The Disruptor itself handles the only producer↔consumer crossing point using **padded sequence cursors**: the producer's write cursor and consumer's read cursor are each surrounded by 56 bytes of padding to guarantee they live on different cache lines. The engine thread never writes to any memory the producer thread reads, and vice versa.

---

## 5. Zero-GC Event Routing — Trie-Cached VarHandle Dispatch

**The problem:** Mapping an incoming POJO (e.g., `MarketDataEvent { instrument="UST_10Y", venue="Btec", bid=99.52 }`) to the correct graph nodes by name (e.g., `"UST_10Y.Btec.bid"`) would require string concatenation, `HashMap` lookups, and temporary `String` objects on every tick.

**The solution:** `GraphAutoRouter` uses a three-layer architecture to achieve zero-allocation routing after warmup:

1. **Annotation-driven discovery** at registration time: `@RoutingKey` fields define the trie path; `@RoutingValue` fields define the payload.
2. **VarHandle field access**: Java's `VarHandle` API (replacing `java.lang.reflect.Field.get()`) provides near-native performance for reading POJO fields without reflection overhead.
3. **Trie-cached execution plans**: On the first occurrence of a new `(instrument, venue)` pair, the router resolves node names to topological `int` IDs and caches them in a `TrieNode`. All subsequent events with the same key combination skip name resolution entirely.

```java
// GraphAutoRouter.RouterCache.route() — steady-state hot path
TrieNode current = root;
for (int i = 0; i < keyHandles.length; i++) {
    String keyStr = (String) keyHandles[i].get(event);  // VarHandle: ~2ns
    current = current.children.get(keyStr);               // HashMap on interned String: ~5ns
}

// Cached int[] — no string concat, no name resolution
int[] ids = current.nodeIds;
for (int i = 0; i < valueHandles.length; i++) {
    graph.update(ids[i], (double) valueHandles[i].get(event)); // Direct topological update
}
```

**Why VarHandles?** Standard reflection (`Field.get()`) allocates a boxed `Object` on every invocation. `VarHandle.get()` returns the primitive directly, eliminating the boxing allocation. The JIT inlines `VarHandle` access into a single `MOV` instruction—the same cost as a direct field read.

---

## 6. Configurable Change Propagation Cutoffs — Graph Pruning in the Inner Loop

**The problem:** Not every recomputation produces a *meaningful* change. A moving average that shifts by 0.0000001 doesn't need to trigger 20 downstream recalculations. Without cutoffs, the engine wastes cycles propagating noise.

**The solution:** Every `ScalarNode` carries a `ScalarCutoff`—a `@FunctionalInterface` operating purely on primitive `double` pairs—that decides whether a value change is significant enough to propagate:

```java
// ScalarCutoffs.java — three strategies, zero allocation
EXACT:               (p, c) -> Double.doubleToRawLongBits(p) != Double.doubleToRawLongBits(c)
absoluteTolerance(t): (p, c) -> Math.abs(c - p) > t
relativeTolerance(t): (p, c) -> Math.abs(c - p) / max(|p|, |c|) > t
```

**Why `doubleToRawLongBits` for EXACT?** This avoids the special-case semantics of `Double.equals()` and `==` for `NaN` and `-0.0`. A raw bitwise comparison compiles to a single `UCOMISD` instruction and handles all edge cases correctly.

**Concrete impact:** In a bond pricing graph with 1,300 nodes and tolerance cutoffs of `1e-8`, a typical tick propagates to ~25 nodes instead of ~200. That's an **8× reduction** in computation per stabilization pass.

### Branch Prediction via Strategy Pattern

A naive approach would use `if/else` chains or `switch` statements to select cutoff behavior per node—introducing an **unpredictable branch** in the inner loop. Instead, each node holds a direct function pointer (the `ScalarCutoff` interface) assigned once at construction. The JIT devirtualizes this into a direct call at the call site—no branch, no dispatch table lookup, no misprediction penalty.

The `SwitchNode` takes this further: it prevents entire subgraphs from being dirtied in the first place. By not setting dirty bits on inactive branches, the inner `while (dirtyNodeBits[w] != 0L)` loop encounters fewer non-zero words, strengthening the "usually false" bias of the outer scan and reducing branch misprediction rates across the entire pass.

`BooleanNode` change detection (`currentValue != previousValue`) compiles to a single `XOR` + `TEST` instruction pair—no floating-point ambiguity, no NaN special-casing. The branch predictor sees this as a simple boolean flip with >95% accuracy in typical signal-processing workloads.

---

## 7. O(1) Streaming Accumulators — Welford's Method for Rolling Statistics

**The problem:** Financial models need rolling variance, correlation, and moving averages over sliding windows. A naive implementation would iterate the entire window on every tick—O(W) per update, where W might be 100 or 1,000.

**The solution:** All windowed calculations use amortized O(1) streaming accumulators backed by pre-allocated circular buffers:

```java
// WindowedNode.java — the circular buffer pattern
double[] window = new double[windowSize];  // Pre-allocated once
int head = 0;

protected double compute() {
    double newValue = input.value();
    if (count < windowSize) {
        accumulator.onAdd(newValue);       // O(1)
    } else {
        double oldest = window[head];
        accumulator.onRemove(oldest);      // O(1)
        accumulator.onAdd(newValue);       // O(1)
    }
    window[head] = newValue;
    head = (head + 1 == windowSize) ? 0 : head + 1;  // Circular advance
    return accumulator.result();           // O(1)
}
```

For variance, the `RollingVariance` accumulator implements **Welford's Online Algorithm**, which maintains running `mean` and `M2` (sum of squared deviations) incrementally:

```java
// RollingVariance.java
public void onAdd(double value) {
    count++;
    double delta = value - mean;
    mean += delta / count;
    m2 += delta * (value - mean);  // Note: uses updated mean
}
```

**Why Welford?** The textbook formula `Var = E[X²] - E[X]²` suffers from **catastrophic cancellation**: when `E[X²]` and `E[X]²` are both large and nearly equal (common for financial prices), their difference loses all significant digits. Welford's method avoids this by tracking deviations from a running mean, maintaining numerical stability across billions of ticks.

### Temporal Cache Locality of Circular Buffers

The `double[] window` array is accessed at the same `head` index on every tick, with the index slowly rotating through the buffer. This creates an ideal temporal locality pattern: the recently-written region of the buffer stays warm in L1 cache because it is re-read (as the "oldest" value to evict) exactly `windowSize` ticks later—typically within milliseconds, far shorter than L1 eviction timescales.

```java
// Tick N:   write window[42], read window[42] as "oldest" was set W ticks ago
// Tick N+1: write window[43], same cache line as window[42] — already in L1
```

For a window size of 100 doubles, the entire buffer is 800 bytes (~13 cache lines). At typical tick rates, the entire working set stays resident in L1/L2 cache. Compare this to a `LinkedList`-based sliding window, where each node is a separate heap object scattered across memory—every eviction would be a cache miss.

---

## Architectural Summary

```
┌─────────────────────────────────────────────────────────────────────┐
│          LMAX Disruptor RingBuffer — Single Writer (§4)             │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐                     │
│  │Tick 1│ │Tick 2│ │Tick 3│ │Tick 4│ │Tick 5│  ← endOfBatch=true   │
│  └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘                     │
│  Padded cursors prevent false sharing across cores                  │
└─────┼────────┼────────┼────────┼────────┼───────────────────────────┘
      │        │        │        │        │
      ▼        ▼        ▼        ▼        ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   GraphAutoRouter — Zero-GC (§5)                    │
│  VarHandle field reads → Trie lookup → cached int[] node IDs        │
│  No string concat · No boxing · No reflection overhead              │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ graph.update(nodeId, value)
                               │ Plain write — no LOCK prefix (§4)
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│              StabilizationEngine — One Pass (§1, §2)                │
│                                                                     │
│  dirtyNodeBits (168 bytes, 3 cache lines — stays in L1)            │
│                                                                     │
│  for each word w:             branch predictor: biased false (§1)  │
│    while word ≠ 0:                                                  │
│      ti = (w<<6) + TZCNT(word)     hardware intrinsic (§1)        │
│      node[ti].stabilize()          JIT inlines final method (§3)  │
│      if changed:                   biased true — speculative (§1)  │
│        for child in CSR[ti]:       sequential cache hit (§2)       │
│          set dirtyNodeBits[child]  plain write, no CAS (§4)        │
│                                                                     │
│  ScalarCutoff: prune if |Δ| < ε   devirtualized by JIT (§6)       │
│  WindowedNode: O(1) Welford       circular buffer in L1 (§7)      │
└─────────────────────────────────────────────────────────────────────┘
```

**Result:** From raw network bytes to fully stabilized 1,300-node graph in under 1 microsecond, with zero garbage collection pressure on the engine thread. Every layer—from I/O to routing to computation—is co-designed with the CPU cache hierarchy, branch predictor, JIT compiler, and memory coherency protocol.
