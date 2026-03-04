package com.trading.drg.node;

import com.trading.drg.api.Node;
import com.trading.drg.api.ScalarValue;

/**
 * Routes a scalar input to one of two output branches based on a boolean condition.
 * <p>
 * When the condition is {@code true}, changes propagate only to {@link #branchTrue()}.
 * When the condition is {@code false}, changes propagate only to {@link #branchFalse()}.
 * The inactive branch suppresses propagation, preserving its last known value.
 * <p>
 * Usage in DSL:
 * <pre>{@code
 * BooleanNode cond   = builder.condition("IsBullish", price, v -> v > 100.0);
 * ConditionalSwitchNode sw = builder.conditionalSwitch("Switch", cond, price);
 * // wire downstream nodes to sw.branchTrue() and sw.branchFalse()
 * }</pre>
 */
public final class ConditionalSwitchNode implements Node {

    private final String name;
    private final BooleanNode condition;
    private final ScalarValue input;

    private final BranchNode trueNode;
    private final BranchNode falseNode;

    // Previous state for change detection
    private double previousInputValue = Double.NaN;
    private boolean previousCondition;
    private boolean initialized;

    public ConditionalSwitchNode(String name, BooleanNode condition, ScalarValue input) {
        this.name = name;
        this.condition = condition;
        this.input = input;
        this.trueNode = new BranchNode(name + ".true", condition, input, true);
        this.falseNode = new BranchNode(name + ".false", condition, input, false);
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Stabilizes the switch node. Returns {@code true} when the condition flips or
     * the input value changes, triggering both branch nodes to be evaluated.
     * Each branch then self-gates: only the active one propagates further.
     */
    @Override
    public boolean stabilize() {
        double newInput = input.value();
        boolean newCondition = condition.booleanValue();

        if (!initialized) {
            initialized = true;
            previousInputValue = newInput;
            previousCondition = newCondition;
            return true;
        }

        boolean conditionChanged = newCondition != previousCondition;
        boolean inputChanged = Double.compare(previousInputValue, newInput) != 0
                || (Double.isNaN(previousInputValue) != Double.isNaN(newInput));

        previousInputValue = newInput;
        previousCondition = newCondition;

        return conditionChanged || inputChanged;
    }

    /** @return the branch node that activates when the condition is {@code true}. */
    public BranchNode branchTrue() {
        return trueNode;
    }

    /** @return the branch node that activates when the condition is {@code false}. */
    public BranchNode branchFalse() {
        return falseNode;
    }

    /** Exposes the condition node for introspection (used by factories). */
    public BooleanNode getCondition() {
        return condition;
    }

    /** Exposes the input node for introspection (used by factories). */
    public ScalarValue getInput() {
        return input;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A gated output of a {@link ConditionalSwitchNode}.
     * <p>
     * Tracks the input value every cycle but only propagates downstream changes
     * when it is the <em>active</em> branch (condition matches {@code isTrue}).
     */
    public static final class BranchNode implements ScalarValue {

        private final String name;
        private final BooleanNode condition;
        private final ScalarValue input;
        private final boolean isTrue;

        private double currentValue = Double.NaN;
        private double previousValue = Double.NaN;
        private boolean previouslyActive;

        public BranchNode(String name, BooleanNode condition, ScalarValue input, boolean isTrue) {
            this.name = name;
            this.condition = condition;
            this.input = input;
            this.isTrue = isTrue;
        }

        @Override
        public String name() {
            return name;
        }

        /**
         * Reads the latest input value. Returns {@code true} (changed) when:
         * <ul>
         *   <li>this branch just became active (condition flipped to match), or</li>
         *   <li>this branch is active AND the value has changed since last cycle.</li>
         * </ul>
         * Returns {@code false} when this branch is inactive (gate is closed).
         */
        @Override
        public boolean stabilize() {
            previousValue = currentValue;
            currentValue = input.value();

            boolean active = (condition.booleanValue() == isTrue);
            boolean wasActive = previouslyActive;
            previouslyActive = active;

            if (!active) {
                return false; // Gate closed: suppress all propagation
            }

            // Newly activated: always propagate so downstream sees the current value
            if (!wasActive) {
                return true;
            }

            // Active and was already active: propagate only on value change
            if (Double.isNaN(previousValue) != Double.isNaN(currentValue)) {
                return true;
            }

            return Double.compare(previousValue, currentValue) != 0;
        }

        @Override
        public double value() {
            return currentValue;
        }

        /** @return {@code true} if this is the true-branch, {@code false} for the false-branch. */
        public boolean isTrue() {
            return isTrue;
        }
    }
}
