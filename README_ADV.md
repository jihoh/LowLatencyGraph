# CoreGraph Advanced Engine Mechanics

## 1. Advanced Performance: The Sparse Bitset (dirtyWords)

The `StabilizationEngine` achieves its extreme O(K) performance (where K is the number of dirty nodes) using a Sparse Bitset called `dirtyNodeBits`. This avoids scanning clean nodes and eliminates objects and garbage collection from the hot path.

### The Bitset Structure
The engine packs the topological status of all nodes into an array of 64-bit `long` primitives.
```java
this.dirtyNodeBits = new long[(topology.nodeCount() + 63) / 64];
```
If a graph has 100 nodes, it allocates an array of two `longs` (`dirtyNodeBits[0]` and `dirtyNodeBits[1]`), providing 128 bits of tracking space.

### Marking a Node Dirty
When an external event updates a node (e.g., Node 3 or Node 70), the engine calculates its exact bit position using bitwise operations:

**For Node 3:**
1. **Find Array Index:** `3 >> 6` (divides by 64) = 0. It belongs in `dirtyNodeBits[0]`.
2. **Find Bit Mask:** `1L << 3` = 8 (Binary `1000`).
3. **Apply Mask:** `dirtyNodeBits[0] |= 8`.

**For Node 70:**
1. **Find Array Index:** `70 >> 6` = 1. It belongs in `dirtyNodeBits[1]`.
2. **Find Bit Mask:** `1L << 70` wraps around to `1L << 6` = 64 (Binary `100 0000`).
3. **Apply Mask:** `dirtyNodeBits[1] |= 64`.

### Sparse Traversal (The Magic)
During the `stabilize()` execution, the engine loops through the `dirtyNodeBits` array.

1. **Instant Skip:** `if (dirtyNodeBits[w] == 0L)` - If a 64-bit block is empty, the engine skips 64 nodes in a single CPU cycle.
2. **Finding the Bit:** If `dirtyNodeBits[0]` is `8` (`1000` in binary), the engine uses the hardware intrinsic `Long.numberOfTrailingZeros(8)` to return exactly `3`. 
3. **Reconstructing the ID:** It calculates `(0 << 6) + 3 = 3`. The engine executes `topology.node(3).stabilize()`.
4. **Clearing the Bit:** It clears the 3rd bit. `dirtyNodeBits[0]` becomes `0L`, and the engine instantly moves to the next block.

For 128 tracked nodes where only Nodes 3 and 70 changed, the engine never checked the other 126 nodes. It executed two array lookups and two O(1) hardware zero-counts, instantly triggering the required computations without allocating a single Java object.


## 2. Cache-Friendly CSR Edge Encoding

In standard Java graphing libraries, a "Node" usually contains a `List<Node> children`. While intuitive, this is disastrous for High-Frequency Trading (HFT):
1. **Memory Fragmentation**: In Java, a `List` of objects is an array of memory pointers scattered randomly across the heap.
2. **Cache Misses**: Following those pointers causes the CPU to constantly pause and fetch arbitrary RAM locations (Cache Misses).
3. **Allocation**: Adding or sorting edges creates Java Garbage.

To solve this, CoreGraph uses **Compressed Sparse Row (CSR)** structure in the `TopologicalOrder.java` class. Outbound graph edges do not exist as Java Objects; they are mathematically flattened into two primitive contiguous `int[]` arrays.

### The Two Arrays

**1. `childrenList[]`**
This array holds every single child connection in the entire graph, squashed flat based on the parent's topological ID. 
If Node 0 has children [1, 2] and Node 1 has child [3], `childrenList` looks like: `[1, 2, 3]`

**2. `childrenOffset[]`**
This array acts as a map to tell us *where* in the `childrenList` a specific node's children start and stop.
- `childrenOffset[nodeId]` = The starting index.
- `childrenOffset[nodeId + 1]` = The stopping index (exclusive).

### Concrete Walkthrough Example
Let's look at a simple 4-Node DAG:
- **Node 0** depends on nothing. It flows into Node 1 and Node 2.
- **Node 1** flows into Node 3.
- **Node 2** flows into Node 3.
- **Node 3** is the output.

When the `GraphBuilder` compiles this graph, it allocates exactly two integer arrays:

```java
int[] childrenOffset = {0, 2, 3, 4, 4}; 
int[] childrenList   = {1, 2, 3, 3};
```

Let's execute the logic the `StabilizationEngine` uses to find children:

**Finding Children for Node 0:**
1. Start Index = `childrenOffset[0]` (which is `0`)
2. End Index = `childrenOffset[0 + 1]` (which is `2`)
3. Loop `childrenList` from index 0 to 2.
4. Engine reads: `childrenList[0]` = **1** and `childrenList[1]` = **2**. 
*(Node 0's children are 1 and 2)*

**Finding Children for Node 1:**
1. Start Index = `childrenOffset[1]` (which is `2`)
2. End Index = `childrenOffset[2]` (which is `3`)
3. Loop `childrenList` from index 2 to 3.
4. Engine reads: `childrenList[2]` = **3**. 
*(Node 1's child is 3)*

**Finding Children for Node 3:**
1. Start Index = `childrenOffset[3]` (which is `4`)
2. End Index = `childrenOffset[4]` (which is `4`)
3. Loop `childrenList` from index 4 to 4. The loop doesn't execute.
*(Node 3 has no children)*

### Why This is 100x Faster
When the Engine stabilizes Node 0 and flags its children as dirty, the CPU pulls a chunk of the `childrenList` array into the blazing-fast L1 CPU Cache. Because the data is primitively contiguous, the CPU's hardware prefetcher guesses what memory is needed next and pre-loads it. The engine achieves sequential memory access without traversing a single Java Object reference pointer.

### The Intersection: CSR + BitSet

Let's tie the two concepts together: **CSR** (how edges are physically mapped) and **dirtyNodeBits** (the BitSet that tracks which nodes hold new data). 

Here is exactly what the engine code looks like when Node `ti` finishes calculating and needs to inform its children that they should wake up and recalculate too:

```java
// 1. Get the slice of the CSR array that belongs to us using the Offset array
final int start = topology.childrenStart(ti);
final int end = topology.childrenEnd(ti);

// 2. Loop directly over the flat Array
for (int ci = start; ci < end; ci++) {
    
    // 3. Extract the integer Topological ID of our child
    int childTi = topology.childAt(ci);
    
    // 4. Convert that integer ID into a Bitmask
    int wordIdx = childTi >> 6;       // Divide by 64 to find the long[] index
    long bitMask = 1L << childTi;     // Shift 1 over to the exact slot
    
    // 5. Flip the bit in the BitSet to '1'
    dirtyNodeBits[wordIdx] |= bitMask;
}
```

By combining the two systems, propagating a dirty wave down a massive graph does not involve instantiating Java Objects (Zero-GC), nor does it involve traversing `Set<Node>` memory pointers (No Cache Misses). It is reduced to pure integer array iteration and bitwise OR logic.


## 3. Node Storage: Zero-Allocation Primitives

In standard Java, numbers are often boxed into objects (e.g., `Double`, `Integer`) and stored in lists or maps. When a graph computes millions of times per second, creating these `Double` objects fills the JVM heap, triggering regular "Stop-the-World" Garbage Collection (GC) pauses that destroy deterministic latency.

CoreGraph solves this by enforcing **100% Primitive Storage** on the hot path. Engine nodes do not contain objects.

### The ScalarNode Base Class
Every standard calculation node extends the `ScalarNode` abstract class. If we look at its raw memory footprint, it holds exactly two 64-bit primitive blocks:

```java
public abstract class ScalarNode implements ScalarValue {
    // ... metadata ...

    // Primitive fields for zero-allocation state
    private double currentValue = Double.NaN;
    private double previousValue = Double.NaN;
    
    // ...
}
```

### The Stabilization Flow
When the graph executes, it never creates a new Java object to return the result. Memory is mathematically changed *in-place*.

```java
    @Override
    public final boolean stabilize() {
        previousValue = currentValue; // Shift the 64-bit register
        currentValue = compute();     // Compute the new 64-bit primitive

        // ... change detection logic ...
    }
```

Because `currentValue` is a primitive `double`, the CPU just overwrites the 64 bits previously stored at that memory location. 

### Cross-Node Communication
Nodes talk to each other without ever passing objects. When `SpreadNode` needs to calculate the difference between `NodeA` and `NodeB`, it relies purely on the `ScalarValue` interface:

```java
public interface ScalarValue extends Node {
    double value(); // Returns the primitive double
}
```

The mathematics inside `SpreadNode` simply look like this:
```java
return a.value() - b.value();
```

Because the `value()` method returns a primitive `double`, the JVM JIT compiler completely inlines the method call. During execution, it physically transforms into a raw Assembly subtraction (`SUBSD`) between two native CPU registers, with zero memory overhead.

This enforces a strict rule in CoreGraph: **Once the topology is built and the JVM warms up, memory allocation (Garbage) drops to precisely 0 bytes per millisecond, forever.**


### Data Locality and the Pointer Chasing Trade-off

You might wonder: *If `ScalarCalcNode` objects instantiate individually, aren't they scattered randomly across the JVM heap? When `NodeA` calls `b.value()`, isn't that pointer chasing?*

**Yes, it is.** 

While the *Topological Traversal* (via CSR arrays and BitSets) is perfectly contiguous and cache-friendly, the *Data Evaluation* step invokes polymorphic methods (`stabilize()`, `value()`) on `Node` interface objects that are scattered throughout the heap. This causes CPU L1 cache misses during data retrieval.

This is an intentional design trade-off in CoreGraph:

1. **Object-Oriented API vs. Data-Oriented Design (DOD):** 
   To completely eliminate pointer chasing, CoreGraph would need to abandon its polymorphic Object-Oriented design. Instead of nodes holding their own `currentValue`, the engine would need to store all graph values in a single giant, flat `double[] values` array.
2. **The Cost of DOD:** 
   Storing all state in a flat array makes the system extremely difficult to extend. Implementing complex nodes (like a `RollingVariance` node that requires internal circular buffers, or a `TimeDecay` node that requires timestamp tracking) becomes a nightmare of managing multiple parallel offset arrays. The Java/JSON API for developers would degrade from intuitive builder patterns into raw integer array manipulation.

CoreGraph sacrifices optimal Data Locality (accepting the pointer-chasing cache miss during `b.value()`) in exchange for a highly extensible, easily comprehensible, polymorphic Graph API. Because the garbage collector is totally silenced (Zero-GC), and the topological traversal skips dead branches in $O(1)$ time, the engine still achieves single-digit microsecond latency, which is more than sufficient for the vast majority of JVM-based trading systems.

### So Why Use CSR At All?
If `b.value()` causes a cache miss anyway, wouldn't a `List<Node> children` be acceptable?

**No.** While we accept the L1 cache miss to retrieve the mathematical `value()`, we absolutely must prevent cache misses during the **Topological Marking Phase**.

When Node 0 recalculates, it must alert Node 1 and Node 2 that they are now dirty. It does this by flipping bits in the `dirtyNodeBits` Bitset.
If Node 0 had a `List<Node> children`, the CPU would do this:
1. Fetch the `ArrayList` object (Cache Miss)
2. Fetch the internal `Object[]` array (Cache Miss)
3. Fetch `Node 1` to get its `topologicalId` (Cache Miss)
4. Bitwise OR the `dirtyNodeBits` array.
5. Fetch `Node 2` to get its `topologicalId` (Cache Miss)
6. Bitwise OR the `dirtyNodeBits` array.

With **CSR Encoding**:
1. The CPU fetches `childrenOffset[0]` to find the bounds (`0` to `2`).
2. The CPU fetches `childrenList[0]` and `childrenList[1]`. Because they are primitives stored contiguously, they arrive in the L1 cache *simultaneously* in a single 64-byte burst fetch.
3. The engine uses those IDs to bitwise OR the `dirtyNodeBits` array.

By using CSR, the engine completes the entire downstream marking phase in just a few CPU cycles, completely eliminating the traversal overhead that a traditional Java Object graph would incur.
