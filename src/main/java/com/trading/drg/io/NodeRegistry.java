package com.trading.drg.io;

import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public final class NodeRegistry {

    public record NodeMetadata(String[] namedInputs, JsonGraphCompiler.NodeFactory factory) {
    }

    private final Map<NodeType, NodeMetadata> registry = new HashMap<>();

    public NodeRegistry() {
        registerBuiltIns();
    }

    public void registerFactory(NodeType type, JsonGraphCompiler.NodeFactory factory) {
        String[] named = null;
        Class<?> nodeClass = type.getNodeClass();

        if (nodeClass != null) {

            // Infer parameter names from 'calculate' method if no explicit @NamedInputs
            // annotation exists
            for (Method m : nodeClass.getDeclaredMethods()) {
                if (m.getName().equals("calculate")) {
                    Parameter[] params = m.getParameters();
                    // Filter out empty/no-arg overrides
                    if (params.length > 0) {
                        named = new String[params.length];
                        for (int i = 0; i < params.length; i++) {
                            named[i] = params[i].getName();
                        }
                        break;
                    }
                }
            }
        }

        registerFactory(type, named, factory);
    }

    public void registerFactory(NodeType type, String[] namedInputs,
            JsonGraphCompiler.NodeFactory factory) {
        registry.put(type, new NodeMetadata(namedInputs, factory));
    }

    public NodeMetadata getMetadata(NodeType type) {
        return registry.get(type);
    }

    public void registerBuiltIns() {
        for (NodeType type : NodeType.values()) {
            if (type.getFactory() != null) {
                registerFactory(type, type.getFactory());
            }
        }
    }

}
