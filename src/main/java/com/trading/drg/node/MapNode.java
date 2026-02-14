package com.trading.drg.node;

import java.util.*;

import com.trading.drg.core.Node;
import com.trading.drg.fn.MapComputeFn;

/**
 * A node that produces a Map of key-value pairs (String -> Double).
 *
 * <p>
 * Useful for reporting nodes, such as "Risk Breakdown" or "P&L Attribution",
 * where
 * the set of keys is known at build time but the values change.
 *
 * <h3>Optimization</h3>
 * To avoid creating new {@code HashMap} objects on every cycle:
 * <ul>
 * <li>The keys are fixed at construction time.</li>
 * <li>Values are stored in a parallel `double[]` array.</li>
 * <li>A flyweight {@link MapWriter} is passed to the computation function to
 * update the array.</li>
 * <li>The public {@link #value()} method returns a cached view (only
 * re-allocated if values change).</li>
 * </ul>
 */
public final class MapNode implements Node<Map<String, Double>> {
    private final String name;
    private final double tolerance;
    private final String[] keys;

    // Internal primitive storage
    private final double[] currentValues;
    private final double[] previousValues;

    // Fast lookup for key -> index
    private final Map<String, Integer> keyIndex;

    private final Node<?>[] inputs;
    private final MapComputeFn fn;
    private final MapWriter writer;

    // Cached output view
    private Map<String, Double> mapView;

    public MapNode(String name, String[] keys, Node<?>[] inputs, MapComputeFn fn, double tolerance) {
        this.name = name;
        this.tolerance = tolerance;
        this.keys = keys.clone();
        this.currentValues = new double[keys.length];
        this.previousValues = new double[keys.length];
        this.keyIndex = new HashMap<>(keys.length * 2);
        for (int i = 0; i < keys.length; i++)
            keyIndex.put(keys[i], i);
        this.inputs = inputs;
        this.fn = fn;
        this.writer = new MapWriter();
        Arrays.fill(currentValues, Double.NaN);
        Arrays.fill(previousValues, Double.NaN);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean stabilize() {
        // Save history
        System.arraycopy(currentValues, 0, previousValues, 0, currentValues.length);

        // Execute logic
        fn.compute(inputs, writer);

        // Check for changes
        for (int i = 0; i < currentValues.length; i++)
            if (Double.isNaN(previousValues[i]) ||
                    Math.abs(currentValues[i] - previousValues[i]) > tolerance) {
                // Invalidate view
                mapView = null;
                return true;
            }
        return false;
    }

    @Override
    public Map<String, Double> value() {
        // Lazy materialization of the Map object
        if (mapView == null) {
            mapView = new HashMap<>(keys.length * 2);
            for (int i = 0; i < keys.length; i++)
                mapView.put(keys[i], currentValues[i]);
        }
        return mapView;
    }

    public double get(String key) {
        Integer idx = keyIndex.get(key);
        if (idx == null)
            throw new IllegalArgumentException("Unknown key: " + key);
        return currentValues[idx];
    }

    public double getByOrdinal(int ordinal) {
        return currentValues[ordinal];
    }

    public String[] keys() {
        return keys;
    }

    public int keyCount() {
        return keys.length;
    }

    /**
     * Flyweight accessor for writing values into the node's internal state.
     */
    public final class MapWriter {
        public void put(String key, double value) {
            Integer idx = keyIndex.get(key);
            if (idx == null)
                throw new IllegalArgumentException("Unknown key: " + key);
            currentValues[idx] = value;
        }

        public void putByOrdinal(int ordinal, double value) {
            currentValues[ordinal] = value;
        }
    }
}
