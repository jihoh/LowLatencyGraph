package com.trading.drg.util;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;

import com.trading.drg.api.DoubleCutoff;

/**
 * Standard implementations of DoubleCutoff.
 *
 * This utility class provides common strategies for determining if a node's
 * value
 * has changed enough to warrant propagation.
 */
public final class DoubleCutoffs {
    private DoubleCutoffs() {
        // Utility class
    }

    /** Always propagates change (returns true). */
    public static final DoubleCutoff ALWAYS = (p, c) -> true;

    /**
     * Never propagates change (returns false).
     * Useful for sinks or side-effect-only nodes.
     */
    public static final DoubleCutoff NEVER = (p, c) -> false;

    /**
     * Propagates if bits are different (handles NaN canonicalization naturally).
     * Ideal for exact equality checks.
     */
    public static final DoubleCutoff EXACT = (p, c) -> Double.doubleToRawLongBits(p) != Double.doubleToRawLongBits(c);

    /**
     * Propagates if absolute difference exceeds tolerance.
     * Logic: |current - previous| > tolerance
     */
    public static DoubleCutoff absoluteTolerance(double tol) {
        return (p, c) -> Math.abs(c - p) > tol;
    }

    /**
     * Propagates if relative difference exceeds tolerance.
     * Logic: |current - previous| / max(|current|, |previous|) > tolerance
     */
    public static DoubleCutoff relativeTolerance(double tol) {
        return (p, c) -> {
            double d = Math.max(Math.abs(p), Math.abs(c));
            return d != 0.0 && Math.abs(c - p) / d > tol;
        };
    }
}
