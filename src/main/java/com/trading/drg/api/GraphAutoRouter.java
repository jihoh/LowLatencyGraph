package com.trading.drg.api;

import com.trading.drg.CoreGraph;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * A zero-allocation event router for updating CoreGraph nodes automatically.
 *
 * Annotate your POJO fields with @RoutingKey and @RoutingValue. The router
 * will seamlessly discover the matching nodes and update them without any
 * String instantiations or GC overhead in the hot path.
 */
public class GraphAutoRouter {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface RoutingKey {
        int order();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface RoutingValue {
        String value() default ""; // Leave empty to use exact field name
    }

    private final CoreGraph graph;

    // Fast O(1) Zero-GC lookup mapping Class pointers to their pre-compiled
    // Tri-Router.
    private final Map<Class<?>, RouterCache> classRouters = new java.util.IdentityHashMap<>();

    private static class TrieNode {
        // Identity/String equals hashmap for navigating String prefixes.
        final Map<String, TrieNode> children = new HashMap<>();
        int[] nodeIds; // Cached topological execution plan
    }

    private class RouterCache {
        private final Field[] keyFields;
        private final VarHandle[] keyHandles;
        private final VarHandle[] valueHandles;
        private final String[] valueSuffixes;
        private final TrieNode root = new TrieNode();

        public RouterCache(Class<?> clazz) {
            MethodHandles.Lookup lookup;
            try {
                lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to access class for MethodHandles", e);
            }

            // Discover Key Fields
            this.keyFields = Arrays.stream(clazz.getDeclaredFields())
                    .filter(f -> f.isAnnotationPresent(RoutingKey.class))
                    .sorted(Comparator.comparingInt(f -> f.getAnnotation(RoutingKey.class).order()))
                    .peek(f -> f.setAccessible(true))
                    .toArray(Field[]::new);

            this.keyHandles = new VarHandle[this.keyFields.length];
            for (int i = 0; i < this.keyFields.length; i++) {
                try {
                    this.keyHandles[i] = lookup.unreflectVarHandle(this.keyFields[i]);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            // Discover Value Fields
            Field[] vFields = Arrays.stream(clazz.getDeclaredFields())
                    .filter(f -> f.isAnnotationPresent(RoutingValue.class))
                    .peek(f -> f.setAccessible(true))
                    .toArray(Field[]::new);

            this.valueHandles = new VarHandle[vFields.length];
            for (int i = 0; i < vFields.length; i++) {
                try {
                    this.valueHandles[i] = lookup.unreflectVarHandle(vFields[i]);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            this.valueSuffixes = new String[vFields.length];

            for (int i = 0; i < vFields.length; i++) {
                RoutingValue ann = vFields[i].getAnnotation(RoutingValue.class);
                String suffix = ann.value();
                if (suffix.isEmpty()) {
                    suffix = vFields[i].getName();
                }
                this.valueSuffixes[i] = suffix;
            }
        }

        public void route(Object event) {
            try {
                TrieNode current = root;

                // 1. Traverse using raw String references from the event (Zero-GC)
                for (int i = 0; i < keyHandles.length; i++) {
                    String keyStr = (String) keyHandles[i].get(event);
                    TrieNode next = current.children.get(keyStr);

                    // Lazily resolve new Instrument/Venue combos (Allocates ONLY ONCE per pair)
                    if (next == null) {
                        next = new TrieNode();
                        current.children.put(keyStr, next);
                    }
                    current = next;
                }

                // 2. Discover Node IDs on first hit
                if (current.nodeIds == null) {
                    StringBuilder prefix = new StringBuilder();
                    for (int i = 0; i < keyFields.length; i++) {
                        prefix.append((String) keyHandles[i].get(event)).append(".");
                    }

                    int[] ids = new int[valueHandles.length];
                    for (int i = 0; i < valueHandles.length; i++) {
                        ids[i] = graph.getNodeId(prefix.toString() + valueSuffixes[i]);
                    }
                    current.nodeIds = ids;
                }

                // 3. Ultra-fast Zero-GC updates using cached Topological array indices
                int[] ids = current.nodeIds;
                for (int i = 0; i < valueHandles.length; i++) {
                    int id = ids[i];
                    if (id >= 0) {
                        graph.update(id, (double) valueHandles[i].get(event));
                    }
                }

            } catch (Exception e) {
                throw new RuntimeException("Routing failed for " + event.getClass(), e);
            }
        }
    }

    public GraphAutoRouter(CoreGraph graph) {
        this.graph = graph;
    }

    /**
     * Registers an Event POJO class so the Router can pre-cache structural
     * reflection properties.
     */
    public GraphAutoRouter registerClass(Class<?> clazz) {
        classRouters.putIfAbsent(clazz, new RouterCache(clazz));
        return this;
    }

    /**
     * Zero-GC Hot Path Routing. Resolves the correct Trie natively based on Class
     * pointer.
     */
    public void route(Object event) {
        RouterCache cache = classRouters.get(event.getClass());
        if (cache != null) {
            cache.route(event);
        }
    }
}
