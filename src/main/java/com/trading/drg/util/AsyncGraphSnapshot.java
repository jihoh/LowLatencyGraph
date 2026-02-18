package com.trading.drg.util;

import com.trading.drg.CoreGraph;
import com.trading.drg.api.Node;
import com.trading.drg.api.ScalarValue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A utility for thread-safe consumption of graph state without object
 * allocation (Zero-GC).
 * <p>
 * This class uses a Sequence Lock (SeqLock) pattern to allow a single writer
 * (the graph thread)
 * to update a set of values while multiple readers (consumer threads) can read
 * a consistent
 * snapshot of those values without locking or blocking.
 * </p>
 */
public class AsyncGraphSnapshot {

    private final AtomicLong sequence = new AtomicLong(0);
    private final double[] values;
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
        this.values = new double[nodeNames.length];
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
        long s = sequence.get();
        // Increment to odd to signal "writing in progress"
        sequence.set(s + 1);

        // Copy values from nodes to internal buffer
        for (int i = 0; i < sourceNodes.length; i++) {
            values[i] = sourceNodes[i].doubleValue();
        }

        // Increment to even to signal "write complete"
        sequence.set(s + 2);
    }

    /**
     * Attempts to read a consistent snapshot into the provided buffer.
     * This method is non-blocking but may fail if a write is in progress.
     *
     * @param buffer The buffer to copy values into. Must be the same length as the
     *               number of watched nodes.
     * @return true if the read was consistent, false if a write was in progress or
     *         occurred during read.
     */
    public boolean read(double[] buffer) {
        if (buffer.length != values.length) {
            throw new IllegalArgumentException("Buffer length must match snapshot size: " + values.length);
        }

        long s1 = sequence.get();
        // If odd, a write is in progress. Fail immediately.
        if ((s1 & 1) != 0)
            return false;

        // Copy values
        System.arraycopy(values, 0, buffer, 0, values.length);

        long s2 = sequence.get();
        // If sequence changed, data is potentially torn/stale.
        return s1 == s2;
    }

    /**
     * Returns the index of a node by name.
     * Useful for accessing the buffer after a successful read.
     */
    public int getIndex(String nodeName) {
        Integer idx = nameToIndex.get(nodeName);
        if (idx == null)
            throw new IllegalArgumentException("Node not found in snapshot: " + nodeName);
        return idx;
    }

    /**
     * Convenience method to read a single value safely.
     * <p>
     * WARNING: Calling this multiple times for different nodes makes NO guarantee
     * that the values are from the same snapshot. For multi-node consistency,
     * use {@link #read(double[])}.
     * </p>
     * 
     * @param nodeName The name of the node to read.
     * @return The latest consistent value.
     */
    public double getDouble(String nodeName) {
        int idx = getIndex(nodeName);

        while (true) {
            long s1 = sequence.get();
            if ((s1 & 1) != 0) {
                Thread.onSpinWait();
                continue;
            }

            double val = values[idx];

            long s2 = sequence.get();
            if (s1 == s2) {
                return val;
            }
        }
    }

    /**
     * Creates a reusable reader for a specific subset of nodes.
     * The reader allocates its buffer once and can be refreshed atomically.
     */
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
        private final int[] indices;
        private final double[] buffer;
        private final Map<String, Integer> localNameToIndex;

        private SnapshotReader(int[] indices, String[] names) {
            this.indices = indices;
            this.buffer = new double[indices.length];
            this.localNameToIndex = new HashMap<>(names.length);
            for (int i = 0; i < names.length; i++) {
                this.localNameToIndex.put(names[i], i);
            }
        }

        /**
         * Attempts to refresh the local buffer from the main snapshot.
         * 
         * @return true if the refresh was consistent, false if a write occurred during
         *         read.
         */
        public boolean refresh() {
            long s1 = sequence.get();
            if ((s1 & 1) != 0)
                return false;

            for (int i = 0; i < indices.length; i++) {
                buffer[i] = values[indices[i]];
            }

            long s2 = sequence.get();
            return s1 == s2;
        }

        /**
         * Spins until a consistent snapshot is obtained.
         * Only returns when the buffer is guaranteed to be consistent.
         */
        public void refreshBlocking() {
            while (!refresh()) {
                Thread.onSpinWait();
            }
        }

        /**
         * Spins until a consistent snapshot is obtained,
         * or until the max attempts are reached.
         * 
         * @param maxAttempts The maximum number of spin-loops before giving up.
         * @return true if consistent snapshot obtained, false if timed out.
         */
        public boolean tryRefresh(int maxAttempts) {
            int attempts = 0;
            while (attempts++ < maxAttempts) {
                if (refresh())
                    return true;
                Thread.onSpinWait();
            }
            return false;
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
