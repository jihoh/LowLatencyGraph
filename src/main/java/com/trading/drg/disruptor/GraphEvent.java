package com.trading.drg.disruptor;

/**
 * A mutable data holder for graph updates, used within the LMAX Disruptor
 * RingBuffer.
 *
 * <p>
 * <b>Flyweight Pattern:</b> Instances of this class are pre-allocated during
 * RingBuffer
 * construction and exist for the lifetime of the application. They are never
 * garbage collected.
 *
 * <p>
 * <b>Fields:</b>
 * <ul>
 * <li>{@code nodeName}: The target source node identifier.</li>
 * <li>{@code doubleValue}: The new value to set.</li>
 * <li>{@code vectorIndex}: For vector nodes, the index to update (-1 for
 * scalars).</li>
 * <li>{@code batchEnd}: Forced end-of-batch flag to trigger stabilization.</li>
 * </ul>
 */
public final class GraphEvent {
    private int nodeId = -1;
    private double doubleValue;
    private int vectorIndex = -1;
    private boolean batchEnd;
    private long sequenceId;

    /**
     * Configures the event for a scalar double update.
     * 
     * @param nodeId   Target node ID (topological index).
     * @param value    New value.
     * @param batchEnd If true, forces a graph stabilization after this event.
     * @param seqId    The sequence ID (for correlation/logging).
     */
    public void setDoubleUpdate(int nodeId, double value, boolean batchEnd, long seqId) {
        this.nodeId = nodeId;
        this.doubleValue = value;
        this.vectorIndex = -1;
        this.batchEnd = batchEnd;
        this.sequenceId = seqId;
    }

    /**
     * Configures the event for a vector element update.
     * 
     * @param nodeId   Target node ID (topological index).
     * @param index    Index in the vector to update.
     * @param value    New value.
     * @param batchEnd If true, forces a graph stabilization after this event.
     * @param seqId    The sequence ID.
     */
    public void setVectorElementUpdate(int nodeId, int index, double value,
            boolean batchEnd, long seqId) {
        this.nodeId = nodeId;
        this.doubleValue = value;
        this.vectorIndex = index;
        this.batchEnd = batchEnd;
        this.sequenceId = seqId;
    }

    public int nodeId() {
        return nodeId;
    }

    public double doubleValue() {
        return doubleValue;
    }

    public int vectorIndex() {
        return vectorIndex;
    }

    public boolean isVectorUpdate() {
        return vectorIndex >= 0;
    }

    public boolean isBatchEnd() {
        return batchEnd;
    }

    public long sequenceId() {
        return sequenceId;
    }

    public void clear() {
        nodeId = -1;
        doubleValue = 0;
        vectorIndex = -1;
        batchEnd = false;
        sequenceId = 0;
    }
}
