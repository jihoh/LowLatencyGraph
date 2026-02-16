package com.trading.drg.api;

/**
 * Specialized Cutoff strategy for primitive doubles.
 *
 * <p>
 * This avoids boxing overhead when performing change detection on double
 * values.
 * Common implementations include:
 * <ul>
 * <li><b>Exact:</b> {@code prev != curr} (handled via {@code Double.compare}
 * for NaNs).</li>
 * <li><b>Epsilon:</b> {@code Math.abs(prev - curr) > epsilon}.</li>
 * </ul>
 */
@FunctionalInterface
public interface DoubleCutoff {

    /**
     * Determines if the double value has changed enough to warrant propagation.
     *
     * @param previous The previous value.
     * @param current  The newly computed value.
     * @return {@code true} if changed; {@code false} otherwise.
     */
    boolean hasChanged(double previous, double current);
}
