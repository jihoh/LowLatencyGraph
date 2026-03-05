package com.trading.drg.api;

/**
 * A specialized Node that can conditionally route updates to specific children.
 * <p>
 * During the stabilization traversal, if a node implements
 * {@link BranchingNode},
 * the engine will query {@link #isBranchActive(String)} for each of its
 * topological
 * children. If it returns {@code false}, the engine skips propagating the dirty
 * flag down that specific edge.
 */
public interface BranchingNode extends Node {

    /**
     * Determines whether the execution dirtiness should propagate to the given
     * child.
     * 
     * @param childName the unique name of the child node being evaluated
     * @return true if the engine should mark this child as dirty
     */
    boolean isBranchActive(String childName);
}
