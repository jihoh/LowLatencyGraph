package com.trading.drg.api;

/**
 * Interface for components that can serialize their internal dynamic state
 * directly to a StringBuilder to avoid GC allocation in the hot path.
 */
public interface DynamicState {
    /**
     * Implementations should append standard JSON key-value pairs separated by commas
     * without surrounding braces. E.g., sb.append("\"state\":").append(this.state);
     */
    void serializeDynamicState(StringBuilder sb);
}
