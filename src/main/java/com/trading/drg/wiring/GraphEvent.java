package com.trading.drg.wiring;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;

/**
 * A mutable data holder for graph updates, used within the LMAX Disruptor
 * RingBuffer.
 *
 * Pattern: Flyweight / Mutable Event
 *
 * Memory Management:
 * Instances of this class are pre-allocated during RingBuffer construction
 * (start of app).
 * They "live forever" and are constantly reused to shuttle data from Producer
 * threads
 * to the Consumer thread. This ensures ZERO Garbage Collection (GC) pressure
 * during
 * runtime operations.
 *
 * Fields:
 * - nodeId: The integer Topological ID of the target source node. Using int
 * instead of String
 * saves lookups and pointer chasing.
 * - doubleValue: The numeric payload.
 * - vectorIndex: If >= 0, indicates this is an update for a specific element of
 * a vector.
 * - batchEnd: A manual override to force stabilization immediately after this
 * event.
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
