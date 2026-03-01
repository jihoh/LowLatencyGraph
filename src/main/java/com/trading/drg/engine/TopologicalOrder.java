package com.trading.drg.engine;

import com.trading.drg.api.Node;
import java.util.*;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Immutable Directed Acyclic Graph (DAG) topography encoded using Compressed
 * Sparse Row (CSR).
 * <p>
 * Optimized for zero-allocation, cache-friendly traversal during stabilization.
 */
@Log4j2
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class TopologicalOrder {
    // The nodes in topological execution order.
    private final Node[] topoOrder;

    // CSR Index: childrenOffset[i] points to the start of node i's children in
    // childrenList.
    // childrenOffset[i+1] points to the end.
    private final int[] childrenOffset;

    // CSR Data: Flattened list of child indices.
    private final int[] childrenList;

    // Metadata: Number of parents for each node (used for debugging/stats)
    private final int[] parentCount;

    // Lookup map for name resolution
    private final Map<String, Integer> nameToIndex;

    // Bitset: packs 64 source flags per long, matching dirtyWords layout
    private final long[] sourceWords;

    public int nodeCount() {
        return topoOrder.length;
    }

    /** Returns the node object at the given topological index. */
    public Node node(int ti) {
        return topoOrder[ti];
    }

    /** Resolves a node name to its topological index. O(1) hash lookup. */
    public int topoIndex(String name) {
        Integer idx = nameToIndex.get(name);
        if (idx == null)
            throw new IllegalArgumentException("Unknown node: " + name);
        return idx;
    }

    public boolean isSource(int ti) {
        return (sourceWords[ti >> 6] & (1L << ti)) != 0;
    }

    public int childCount(int ti) {
        return childrenOffset[ti + 1] - childrenOffset[ti];
    }

    public int child(int ti, int i) {
        return childrenList[childrenOffset[ti] + i];
    }

    public int childrenStart(int ti) {
        return childrenOffset[ti];
    }

    public int childrenEnd(int ti) {
        return childrenOffset[ti + 1];
    }

    public int childAt(int flatIndex) {
        return childrenList[flatIndex];
    }

    public int parentCount(int ti) {
        return parentCount[ti];
    }

    // Internal array is not exposed to prevent mutation of immutable topology.

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing and validating a TopologicalOrder via Kahn's
     * algorithm.
     */
    public static final class Builder {
        private final List<Node> nodes = new ArrayList<>();
        private final Map<String, Integer> nameToIdx = new HashMap<>();
        private final Map<Integer, List<Integer>> forwardEdges = new HashMap<>();
        private final Set<Integer> sourceIndices = new HashSet<>();

        public Builder addNode(Node node) {
            if (nameToIdx.containsKey(node.name()))
                throw new IllegalArgumentException("Duplicate node name: " + node.name());
            int idx = nodes.size();
            nodes.add(node);
            nameToIdx.put(node.name(), idx);
            forwardEdges.put(idx, new ArrayList<>());
            return this;
        }

        public Builder addEdge(String from, String to) {
            if (from.equals(to))
                throw new IllegalArgumentException("Self-edge not allowed: " + from);
            forwardEdges.get(requireIndex(from)).add(requireIndex(to));
            return this;
        }

        public Builder markSource(String name) {
            sourceIndices.add(requireIndex(name));
            return this;
        }

        private int requireIndex(String name) {
            Integer idx = nameToIdx.get(name);
            if (idx == null)
                throw new IllegalArgumentException("Unknown node: " + name);
            return idx;
        }

        /**
         * Compiles the graph, executing cycle detection and topological sorting.
         */
        public TopologicalOrder build() {
            int n = nodes.size();
            int[] inDegree = new int[n];

            // 1. Calculate in-degrees
            for (var entry : forwardEdges.entrySet())
                for (int child : entry.getValue())
                    inDegree[child]++;

            // 2. Initialize queue with nodes having in-degree 0
            int[] queue = new int[n];
            int head = 0, tail = 0;
            for (int i = 0; i < n; i++)
                if (inDegree[i] == 0)
                    queue[tail++] = i;

            // 3. Process queue (Kahn's algorithm)
            int[] topoMap = new int[n], reverseMap = new int[n];
            int topoIdx = 0;
            while (head < tail) {
                int curr = queue[head++];
                topoMap[curr] = topoIdx;
                reverseMap[topoIdx] = curr;
                topoIdx++;
                for (int child : forwardEdges.get(curr))
                    if (--inDegree[child] == 0)
                        queue[tail++] = child; // Child is now ready
            }
            if (topoIdx != n)
                throw new IllegalStateException("Cycle detected! Processed " + topoIdx + " of " + n);

            // 4. Construct compact arrays
            Node[] orderedNodes = new Node[n];
            long[] srcWords = new long[(n + 63) / 64];
            int[] parentCounts = new int[n];
            Map<String, Integer> newNameToIndex = new HashMap<>(n * 2);

            for (int ti = 0; ti < n; ti++) {
                int origIdx = reverseMap[ti];
                orderedNodes[ti] = nodes.get(origIdx);
                if (sourceIndices.contains(origIdx)) {
                    srcWords[ti >> 6] |= (1L << ti);
                }
                newNameToIndex.put(orderedNodes[ti].name(), ti);
            }

            // 5. Build CSR structure
            int totalEdges = 0;
            int[] offsets = new int[n + 1];
            for (int ti = 0; ti < n; ti++) {
                List<Integer> children = forwardEdges.get(reverseMap[ti]);
                offsets[ti + 1] = offsets[ti] + children.size();
                totalEdges += children.size();
            }

            int[] flatChildren = new int[totalEdges];
            for (int ti = 0; ti < n; ti++) {
                List<Integer> children = forwardEdges.get(reverseMap[ti]);
                int base = offsets[ti];
                for (int j = 0; j < children.size(); j++) {
                    int childTi = topoMap[children.get(j)];
                    flatChildren[base + j] = childTi;
                    parentCounts[childTi]++;
                }
            }
            return new TopologicalOrder(orderedNodes, offsets, flatChildren, parentCounts, newNameToIndex, srcWords);
        }
    }
}
