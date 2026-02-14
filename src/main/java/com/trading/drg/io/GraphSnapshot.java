package com.trading.drg.io;

import java.util.Arrays;

import com.trading.drg.core.Snapshotable;
import com.trading.drg.core.StabilizationEngine;
import com.trading.drg.core.TopologicalOrder;

/**
 * Capture and restore mechanism for graph state.
 *
 * <p>
 * A snapshot is a binary blob containing the state of all {@link Snapshotable}
 * nodes
 * at a specific epoch. It allows for:
 * <ul>
 * <li><b>State Replication:</b> Sending the graph state to a hot standby.</li>
 * <li><b>Time Travel:</b> Accessing historical states for debugging.</li>
 * <li><b>Persistence:</b> Saving the state to disk for recovery.</li>
 * </ul>
 */
public final class GraphSnapshot {
    private final byte[] buffer;
    private final TopologicalOrder topology;
    private long capturedEpoch;
    private int bytesUsed;

    /**
     * Pre-allocates a buffer large enough to hold the state of all snapshotable
     * nodes.
     * 
     * @param topology The graph topology.
     */
    public GraphSnapshot(TopologicalOrder topology) {
        this.topology = topology;
        int totalSize = 0;
        for (int i = 0; i < topology.nodeCount(); i++)
            if (topology.node(i) instanceof Snapshotable s)
                totalSize += s.snapshotSizeBytes();
        // 8 bytes for epoch + data
        this.buffer = new byte[totalSize + 8];
    }

    /**
     * Captures the current state of the engine into the buffer.
     * 
     * @param engine The engine to snapshot.
     */
    public void capture(StabilizationEngine engine) {
        this.capturedEpoch = engine.epoch();
        int offset = 0;

        // Write Epoch (long)
        long e = capturedEpoch;
        for (int i = 7; i >= 0; i--)
            buffer[offset++] = (byte) (e >> (i * 8));

        // Write Node States
        for (int i = 0; i < topology.nodeCount(); i++)
            if (topology.node(i) instanceof Snapshotable s)
                offset += s.snapshotTo(buffer, offset);
        this.bytesUsed = offset;
    }

    /**
     * Restores the engine state from the buffer.
     * Marks all restored nodes as dirty to ensure consistency.
     * 
     * @param engine The engine to restore.
     */
    public void restore(StabilizationEngine engine) {
        int offset = 8; // Skip epoch
        for (int i = 0; i < topology.nodeCount(); i++)
            if (topology.node(i) instanceof Snapshotable s) {
                offset += s.restoreFrom(buffer, offset);
                engine.markDirty(i);
            }
    }

    public long capturedEpoch() {
        return capturedEpoch;
    }

    public int bytesUsed() {
        return bytesUsed;
    }

    /**
     * Returns a copy of the snapshot data.
     */
    public byte[] exportBytes() {
        return Arrays.copyOf(buffer, bytesUsed);
    }
}
