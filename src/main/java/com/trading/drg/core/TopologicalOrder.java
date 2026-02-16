package com.trading.drg.core;

import java.util.*;

import lombok.extern.log4j.Log4j2;

/**
 * Topology — CSR-encoded static DAG (Directed Acyclic Graph).
 *
 * <p>
 * This class represents the immutable structure of the graph after it has been
 * built.
 * It is optimized for extremely fast traversal during the stabilization phase.
 *
 * <h3>Optimization Strategy</h3>
 * Instead of using an object-oriented graph where each Node object holds a list
 * of its children,
 * we uses a <b>Compressed Sparse Row (CSR)</b> format (similar to sparse matrix
 * representations).
 * <p>
 * The graph is flattened into structure-of-arrays:
 * <ul>
 * <li><b>topoOrder:</b> All nodes sorted topologically (dependencies before
 * dependents).</li>
 * <li><b>childrenList:</b> A single flattened int array containing the indices
 * of all children for all nodes.</li>
 * <li><b>childrenOffset:</b> An index array pointing to the start of each
 * node's children in {@code childrenList}.</li>
 * </ul>
 *
 * <p>
 * This layout ensures that iterating over valid propagation paths is
 * cache-friendly and involves
 * zero object allocation or pointer chasing.
 */
@Log4j2
public final class TopologicalOrder {
    // The nodes in topological execution order.
    private final Node<?>[] topoOrder;

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

    // Bitset-like array to quickly identify source nodes
    private final boolean[] isSource;

    private TopologicalOrder(Node<?>[] topoOrder, int[] childrenOffset, int[] childrenList,
            int[] parentCount, Map<String, Integer> nameToIndex, boolean[] isSource) {
        this.topoOrder = topoOrder;
        this.childrenOffset = childrenOffset;
        this.childrenList = childrenList;
        this.parentCount = parentCount;
        this.nameToIndex = nameToIndex;
        this.isSource = isSource;
    }

    public int nodeCount() {
        return topoOrder.length;
    }

    /** Returns the node object at the given topological index. */
    public Node<?> node(int ti) {
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
        return isSource[ti];
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

    public Node<?>[] topoOrder() {
        return topoOrder;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing the TopologicalOrder.
     * Handles cycle detection and topological sorting.
     */
    public static final class Builder {
        private final List<Node<?>> nodes = new ArrayList<>();
        private final Map<String, Integer> nameToIdx = new HashMap<>();
        private final Map<Integer, List<Integer>> forwardEdges = new HashMap<>();
        private final Set<Integer> sourceIndices = new HashSet<>();

        public Builder addNode(Node<?> node) {
            if (nameToIdx.containsKey(node.name()))
                throw new IllegalArgumentException("Duplicate node name: " + node.name());
            int idx = nodes.size();
            nodes.add(node);
            nameToIdx.put(node.name(), idx);
            forwardEdges.put(idx, new ArrayList<>());
            return this;
        }

        public Builder addEdge(String from, String to) {
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
         * Compiles the graph.
         * <p>
         * Performs Kahn's algorithm for topological sorting and cycle detection.
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
            Node<?>[] orderedNodes = new Node<?>[n];
            boolean[] isSrc = new boolean[n];
            int[] parentCounts = new int[n];
            Map<String, Integer> newNameToIndex = new HashMap<>(n * 2);

            for (int ti = 0; ti < n; ti++) {
                int origIdx = reverseMap[ti];
                orderedNodes[ti] = nodes.get(origIdx);
                isSrc[ti] = sourceIndices.contains(origIdx);
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
            return new TopologicalOrder(orderedNodes, offsets, flatChildren, parentCounts, newNameToIndex, isSrc);
        }
    }
}
