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

        // Initialize view once
        this.mapView = new FlyweightMap();
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
        for (int i = 0; i < currentValues.length; i++) {
            if (Double.isNaN(previousValues[i]) ||
                    Math.abs(currentValues[i] - previousValues[i]) > tolerance) {
                // View is always valid, just pointing to changed data
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<String, Double> value() {
        return mapView;
    }

    // Zero-allocation Map implementation
    private class FlyweightMap extends AbstractMap<String, Double> {
        private final FlyweightEntrySet entrySet = new FlyweightEntrySet();

        @Override
        public Double get(Object key) {
            Integer idx = keyIndex.get(key);
            return idx == null ? null : currentValues[idx];
        }

        @Override
        public boolean containsKey(Object key) {
            return keyIndex.containsKey(key);
        }

        @Override
        public int size() {
            return keys.length;
        }

        @Override
        public Set<Map.Entry<String, Double>> entrySet() {
            return entrySet;
        }
    }

    private class FlyweightEntrySet extends AbstractSet<Map.Entry<String, Double>> {
        @Override
        public Iterator<Map.Entry<String, Double>> iterator() {
            return new FlyweightIterator();
        }

        @Override
        public int size() {
            return keys.length;
        }
    }

    private class FlyweightIterator implements Iterator<Map.Entry<String, Double>> {
        private int index = 0;
        private final FlyweightEntry entry = new FlyweightEntry();

        @Override
        public boolean hasNext() {
            return index < keys.length;
        }

        @Override
        public Map.Entry<String, Double> next() {
            if (index >= keys.length)
                throw new NoSuchElementException();
            entry.setIndex(index++);
            return entry;
        }
    }

    private class FlyweightEntry implements Map.Entry<String, Double> {
        private int index;

        void setIndex(int index) {
            this.index = index;
        }

        @Override
        public String getKey() {
            return keys[index];
        }

        @Override
        public Double getValue() {
            return currentValues[index];
        }

        @Override
        public Double setValue(Double value) {
            throw new UnsupportedOperationException("MapNode view is immutable via iterator");
        }

        @Override
        public String toString() {
            return keys[index] + "=" + currentValues[index];
        }

        @Override
        public int hashCode() {
            return keys[index].hashCode() ^ Double.hashCode(currentValues[index]);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            Object k1 = getKey();
            Object k2 = e.getKey();
            if (k1 == k2 || (k1 != null && k1.equals(k2))) {
                Object v1 = getValue();
                Object v2 = e.getValue();
                return v1 == v2 || (v1 != null && v1.equals(v2));
            }
            return false;
        }
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
