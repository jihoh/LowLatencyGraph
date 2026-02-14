package com.trading.drg.core;

/**
 * Interface for nodes that support state snapshotting and restoration.
 *
 * <p>
 * This capability is used to:
 * <ul>
 * <li><b>Persist State:</b> Save the graph state to disk or a database.</li>
 * <li><b>Replication:</b> Replicate state to a standby replica.</li>
 * <li><b>Debugging:</b> capture the exact state of the engine at the moment of
 * an error.</li>
 * </ul>
 *
 * <p>
 * Not all nodes need to implement this; typically only stateful nodes (like
 * Sources
 * or accumulators) need to be snapshotable. Computed nodes can simply re-derive
 * their
 * state from their inputs.
 */
public interface Snapshotable {

    /**
     * Writes the node's internal state to the provided byte buffer.
     *
     * @param buffer The destination buffer.
     * @param offset The index in the buffer to start writing at.
     * @return The number of bytes written.
     */
    int snapshotTo(byte[] buffer, int offset);

    /**
     * Restores the node's internal state from the provided byte buffer.
     *
     * @param buffer The source buffer.
     * @param offset The index in the buffer to start reading from.
     * @return The number of bytes read.
     */
    int restoreFrom(byte[] buffer, int offset);

    /**
     * Returns the number of bytes required to store this node's state.
     *
     * @return Size in bytes.
     */
    int snapshotSizeBytes();
}
