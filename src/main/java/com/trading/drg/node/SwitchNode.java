package com.trading.drg.node;

import com.trading.drg.api.BranchingNode;
import com.trading.drg.api.ScalarValue;

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

    // Topology router targets (Using arrays for L1 cache locality on the hot path)
    private String[] trueChildren = new String[0];
    private String[] falseChildren = new String[0];

    private double currentValue = Double.NaN;
    private boolean lastConditionResult;

    public SwitchNode(String name, ScalarValue input, BooleanNode condition) {
        this.name = name;
        this.input = input;
        this.condition = condition;
    }

    public void addTrueBranch(String childName) {
        String[] newArr = new String[trueChildren.length + 1];
        System.arraycopy(trueChildren, 0, newArr, 0, trueChildren.length);
        newArr[trueChildren.length] = childName;
        trueChildren = newArr;
    }

    public void addFalseBranch(String childName) {
        String[] newArr = new String[falseChildren.length + 1];
        System.arraycopy(falseChildren, 0, newArr, 0, falseChildren.length);
        newArr[falseChildren.length] = childName;
        falseChildren = newArr;
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
        for (int i = 0; i < trueChildren.length; i++) {
            if (trueChildren[i].equals(childName)) {
                return lastConditionResult;
            }
        }
        for (int i = 0; i < falseChildren.length; i++) {
            if (falseChildren[i].equals(childName)) {
                return !lastConditionResult;
            }
        }
        return true;
    }
}
