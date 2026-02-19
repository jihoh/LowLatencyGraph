package com.trading.drg.util;

import com.trading.drg.CoreGraph;
import com.trading.drg.api.Node;
import com.trading.drg.api.ScalarValue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A utility for thread-safe consumption of graph state without object
 * allocation (Zero-GC) using Triple Buffering.
 * <p>
 * This class uses a Triple Buffering pattern to allow a single writer
 * (the graph thread) and a single reader (consumer thread) to exchange
 * full snapshots without blocking or retrying.
 * </p>
 * <p>
 * Buffer Management:
 * 1. DIRTY (Writer-owned): Writer writes here.
 * 2. CLEAN (Shared Atomic): The latest complete snapshot. Writer swaps DIRTY
 * <-> CLEAN to publish.
 * 3. SNAPSHOT (Reader-owned): Reader reads here. Reader swaps SNAPSHOT <->
 * CLEAN to refresh.
 * </p>
 */
public class AsyncGraphSnapshot {

    // Triple Buffering State
    // [3][numNodes]
    private final double[][] buffers;

    // The index of the buffer with the latest complete snapshot.
    // Acts as the exchange slot between Writer and Reader.
    private final AtomicInteger cleanIdx = new AtomicInteger(0);

    // The index the writer is currently writing to (Writer-local)
    private int dirtyIdx = 1;

    private final ScalarValue[] sourceNodes;
    private final Map<String, Integer> nameToIndex;

    /**
     * Creates a new snapshot watcher for the given nodes.
     *
     * @param graph     The CoreGraph instance.
     * @param nodeNames The names of the nodes to watch. All nodes must implement
     *                  ScalarValue.
     */
    public AsyncGraphSnapshot(CoreGraph graph, String... nodeNames) {
        this.buffers = new double[3][nodeNames.length];
        this.sourceNodes = new ScalarValue[nodeNames.length];
        this.nameToIndex = new HashMap<>(nodeNames.length);

        for (int i = 0; i < nodeNames.length; i++) {
            String name = nodeNames[i];
            Node<?> node = graph.getNode(name);
            if (node instanceof ScalarValue sv) {
                this.sourceNodes[i] = sv;
            } else {
                throw new IllegalArgumentException("Node " + name + " must implement ScalarValue");
            }
            this.nameToIndex.put(name, i);
        }
    }

    /**
     * Updates the snapshot from the graph state.
     * This method MUST be called from the Graph Reactor thread (e.g., in
     * postStabilizationCallback).
     *
     * @param epoch Current graph epoch (unused, but matches callback signature).
     * @param count Nodes recomputed (unused).
     */
    public void update(long epoch, int count) {
        // 1. Write to the dirty buffer (Writer-Only)
        double[] dirtyBuffer = buffers[dirtyIdx];
        for (int i = 0; i < sourceNodes.length; i++) {
            dirtyBuffer[i] = sourceNodes[i].doubleValue();
        }

        // 2. Publish: Atomic Exchange (Swap Dirty <-> Clean)
        // The old 'clean' becomes our new 'dirty' for the next write.
        // We give our 'dirty' (with new data) to 'clean' for the reader to find.
        dirtyIdx = cleanIdx.getAndSet(dirtyIdx);
    }

    /**
     * Convenience method to read a single value directly.
     * Note: This does NOT guarantee consistency across multiple calls.
     * Use a Reader for consistent views.
     * 
     * @param nodeName The name of the node to read.
     * @return The latest value.
     */
    public double getDouble(String nodeName) {
        int idx = getIndex(nodeName);
        // Just peek at the clean buffer.
        // Technically, 'cleanIdx' could change while we read (if writer is fast),
        // so we might read from a buffer that is being recycled.
        // For strict correctness, use createReader().refresh().
        return buffers[cleanIdx.get()][idx];
    }

    /**
     * Returns the index of a node by name.
     */
    public int getIndex(String nodeName) {
        Integer idx = nameToIndex.get(nodeName);
        if (idx == null)
            throw new IllegalArgumentException("Node not found in snapshot: " + nodeName);
        return idx;
    }

    /**
     * Creates a reusable reader for a specific subset of nodes.
     * The reader allocates its buffer once and can be refreshed atomically.
     */
    public SnapshotReader createReader(String... nodeNames) {
        int[] indices = new int[nodeNames.length];
        for (int i = 0; i < nodeNames.length; i++) {
            indices[i] = getIndex(nodeNames[i]);
        }
        return new SnapshotReader(indices, nodeNames);
    }

    /**
     * Creates a reusable reader for ALL nodes in the snapshot.
     */
    public SnapshotReader createReader() {
        return createReader(nameToIndex.keySet().toArray(new String[0]));
    }

    /**
     * A helper class that reads a consistent subset of the snapshot into a local
     * buffer.
     */
    public class SnapshotReader {
        // The index this reader currently owns and reads from.
        // Started at 2 to avoid conflict with Writer(1) and Clean(0).
        private int snapshotIdx = 2;

        private final int[] indices;
        private final double[] buffer;
        private final Map<String, Integer> localNameToIndex;

        private SnapshotReader(int[] indices, String[] names) {
            this.indices = indices;
            this.localNameToIndex = new HashMap<>(names.length);
            for (int i = 0; i < names.length; i++) {
                this.localNameToIndex.put(names[i], i);
            }
            // Kept for API compatibility and subsetting, but backing data is in
            // buffers[snapshotIdx]
            this.buffer = new double[indices.length];
        }

        /**
         * Swaps the reader's view with the latest clean buffer.
         * Guaranteed to succeed immediately. No waiting, no retries.
         * Wait-Free.
         */
        public void refresh() {
            // Atomic Exchange (Swap Owner Snapshot <-> Clean)
            // The old 'clean' becomes our new 'snapshot'.
            // Our old 'snapshot' goes back to the 'clean' slot to be recycled by writer.
            snapshotIdx = cleanIdx.getAndSet(snapshotIdx);

            // For subsetting, we copy from the full snapshot to our local small buffer.
            // This preserves the `get(i)` API and allows the user to have a stable array.
            double[] source = buffers[snapshotIdx];
            for (int i = 0; i < indices.length; i++) {
                buffer[i] = source[indices[i]];
            }
        }

        /**
         * @deprecated Use {@link #refresh()} instead. Always returns true.
         */
        @Deprecated
        public boolean tryRefresh(int maxAttempts) {
            refresh();
            return true;
        }

        public double get(int index) {
            return buffer[index];
        }

        /**
         * Convenience method to get a value by name from the consistent local buffer.
         */
        public double get(String name) {
            Integer idx = localNameToIndex.get(name);
            if (idx == null) {
                throw new IllegalArgumentException("Node not found in reader: " + name);
            }
            return buffer[idx];
        }

        public double[] buffer() {
            return buffer;
        }
    }
}
