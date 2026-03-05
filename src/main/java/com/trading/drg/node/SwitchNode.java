package com.trading.drg.node;

import com.trading.drg.api.BranchingNode;
import com.trading.drg.api.ScalarValue;

import java.util.HashSet;
import java.util.Set;

/**
 * A branching node that routes execution to specific child paths based on a
 * boolean condition.
 * <p>
 * Evaluates the condition, stores its current input value, and then acts as a
 * dynamic router
 * for the {@link com.trading.drg.engine.StabilizationEngine}, allowing O(1)
 * skipping of inactive branches.
 */
public class SwitchNode implements BranchingNode, ScalarValue {
    private final String name;
    private final ScalarValue input;
    private final BooleanNode condition;

    // Topology router targets
    private final Set<String> trueChildren = new HashSet<>();
    private final Set<String> falseChildren = new HashSet<>();

    private double currentValue = Double.NaN;
    private boolean lastConditionResult;

    public SwitchNode(String name, ScalarValue input, BooleanNode condition) {
        this.name = name;
        this.input = input;
        this.condition = condition;
    }

    public void addTrueBranch(String childName) {
        trueChildren.add(childName);
    }

    public void addFalseBranch(String childName) {
        falseChildren.add(childName);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public double value() {
        return currentValue;
    }

    @Override
    public boolean stabilize() {
        // Execute the gating condition
        lastConditionResult = condition.booleanValue();

        // Pass through the underlying value
        double newVal = input.value();
        // Return true to trigger the engine to check branches (or if value functionally
        // changed)
        if (Double.compare(newVal, currentValue) != 0) {
            currentValue = newVal;
        }

        // We must return true so the engine invokes isBranchActive for our children
        return true;
    }

    @Override
    public boolean isBranchActive(String childName) {
        if (trueChildren.contains(childName)) {
            return lastConditionResult;
        }
        if (falseChildren.contains(childName)) {
            return !lastConditionResult;
        }
        // If a child (like a telemetry reporter) isn't explicitly registered as a
        // branch,
        // it always receives updates natively.
        return true;
    }
}
