package com.trading.drg.util;

import com.trading.drg.api.ScalarCutoff;

/** Standard implementations of {@link ScalarCutoff} for change detection. */
public final class ScalarCutoffs {
    private ScalarCutoffs() {
        // Utility class
    }

    /** Always propagates change (returns true). */
    public static final ScalarCutoff ALWAYS = (p, c) -> true;

    /** Never propagates change. Useful for sink/side-effect nodes. */
    public static final ScalarCutoff NEVER = (p, c) -> false;

    /**
     * Propagates if bits differ (exact equality check, handles NaN canonically).
     */
    public static final ScalarCutoff EXACT = (p, c) -> Double.doubleToRawLongBits(p) != Double.doubleToRawLongBits(c);

    /** Propagates if absolute difference exceeds tolerance. */
    public static ScalarCutoff absoluteTolerance(double tol) {
        return (p, c) -> {
            if (Double.isNaN(p) != Double.isNaN(c))
                return true;
            return Math.abs(c - p) > tol;
        };
    }

    /** Propagates if relative difference exceeds tolerance. */
    public static ScalarCutoff relativeTolerance(double tol) {
        return (p, c) -> {
            if (Double.isNaN(p) != Double.isNaN(c))
                return true;
            double d = Math.max(Math.abs(p), Math.abs(c));
            return d != 0.0 && Math.abs(c - p) / d > tol;
        };
    }
}
