package com.trading.drg.io;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;

import java.util.*;

import com.trading.drg.util.ScalarCutoffs;

/**
 * Compiles a JSON {@link GraphDefinition} into a live, executable graph.
 *
 * <p>
 * This compiler operates in two passes:
 * <ol>
 * <li><b>Instantiation:</b> Creates all nodes based on registered factories
 * (types).</li>
 * <li><b>Wiring:</b> Connects dependencies based on the topology.</li>
 * </ol>
 *
 * <p>
 * Supports plugins via {@link #registerFactory(String, NodeFactory)}.
 */
public final class JsonGraphCompiler {
    private final NodeRegistry registry = new NodeRegistry();

    /**
     * Registers a factory for a specific node type enum.
     */
    public JsonGraphCompiler registerFactory(NodeType type, NodeRegistry.NodeMetadata meta) {
        registry.registerFactory(type, meta.namedInputs(), meta.factory());
        return this;
    }

    /**
     * Compiles the definition into a graph.
     * 
     * @param def The graph definition.
     * @return A container holding the engine and name lookup map.
     */
    public CompiledGraph compile(GraphDefinition def) {
        var graphInfo = def.getGraph();

        // 0. Pre-process templates
        Map<String, GraphDefinition.TemplateDef> templateMap = new HashMap<>();
        if (graphInfo.getTemplates() != null) {
            for (var t : graphInfo.getTemplates()) {
                templateMap.put(t.getName(), t);
            }
        }

        var nodeDefs = expandTemplates(graphInfo.getNodes(), templateMap);

        // Capture original JSON sequence
        List<String> originalOrder = nodeDefs.stream()
                .map(GraphDefinition.NodeDef::getName)
                .toList();

        // 1. Sort dependencies topologically
        nodeDefs = topologicalSort(nodeDefs);

        Map<String, Node<?>> nodesByName = new HashMap<>(nodeDefs.size() * 2);
        Map<String, String> logicalTypes = new HashMap<>(nodeDefs.size() * 2);
        Map<String, String> descriptions = new HashMap<>(nodeDefs.size() * 2);
        Map<String, Map<String, String>> edgeLabels = new HashMap<>(nodeDefs.size() * 2);
        var topo = TopologicalOrder.builder();

        // 2. Instantiate and Build Topology
        for (var nd : nodeDefs) {
            NodeType type = NodeType.fromString(nd.getType());
            logicalTypes.put(nd.getName(), type.name());

            String configDesc = nd.getDescription();
            if (configDesc != null) {
                descriptions.put(nd.getName(), configDesc);
            }
        }

        for (var nd : nodeDefs) {
            NodeType type = NodeType.fromString(nd.getType());
            NodeRegistry.NodeMetadata meta = registry.getMetadata(type);
            if (meta == null) {
                throw new IllegalArgumentException("No NodeFactory for type " + type + " in node " + nd.getName());
            }

            // Resolve dependencies array
            Node<?>[] deps;

            if (nd.getInputs() != null && !nd.getInputs().isEmpty()) {
                if (meta.namedInputs() != null) {
                    deps = new Node<?>[meta.namedInputs().length];
                    for (int i = 0; i < deps.length; i++) {
                        String inputKey = meta.namedInputs()[i];
                        String depName = nd.getInputs().get(inputKey);
                        if (depName == null) {
                            throw new IllegalArgumentException(
                                    "Missing @NamedInput '" + inputKey + "' in JSON inputs map for node "
                                            + nd.getName());
                        }

                        edgeLabels.computeIfAbsent(nd.getName(), k -> new HashMap<>()).put(depName, inputKey);

                        Node<?> upstream = nodesByName.get(depName);
                        if (upstream == null)
                            throw new IllegalArgumentException(
                                    "Dependency " + depName + " not found for node " + nd.getName());

                        deps[i] = upstream;
                    }
                } else {
                    // Unbounded varargs node (e.g. AVERAGE)
                    deps = new Node<?>[nd.getInputs().size()];
                    int i = 0;
                    List<String> sortedKeys = new ArrayList<>(nd.getInputs().keySet());
                    Collections.sort(sortedKeys);
                    for (String inputKey : sortedKeys) {
                        String depName = nd.getInputs().get(inputKey);
                        edgeLabels.computeIfAbsent(nd.getName(), k -> new HashMap<>()).put(depName, inputKey);
                        Node<?> upstream = nodesByName.get(depName);
                        if (upstream == null)
                            throw new IllegalArgumentException(
                                    "Dependency " + depName + " not found for node " + nd.getName());
                        deps[i++] = upstream;
                    }
                }
            } else {
                deps = new Node<?>[0];
            }

            Node<?> node = meta.factory().create(nd.getName(),
                    nd.getProperties() != null ? nd.getProperties() : Collections.emptyMap(),
                    deps);
            nodesByName.put(nd.getName(), node);

            topo.addNode(node);
            if (node instanceof SourceNode)
                topo.markSource(nd.getName());

            if (nd.getInputs() != null) {
                for (String dep : nd.getInputs().values()) {
                    topo.addEdge(dep, nd.getName());
                }
            }
        }

        return new CompiledGraph(graphInfo.getName(), graphInfo.getVersion(),
                new StabilizationEngine(topo.build()), nodesByName, logicalTypes, descriptions,
                originalOrder,
                edgeLabels);
    }

    private List<GraphDefinition.NodeDef> topologicalSort(List<GraphDefinition.NodeDef> nodes) {
        Map<String, GraphDefinition.NodeDef> byName = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> outEdges = new HashMap<>();

        for (var n : nodes) {
            byName.put(n.getName(), n);
            inDegree.put(n.getName(), 0);
            outEdges.put(n.getName(), new ArrayList<>());
        }

        for (var n : nodes) {

            // Track dependencies from new Named Inputs map
            if (n.getInputs() != null) {
                for (String dep : n.getInputs().values()) {
                    if (!byName.containsKey(dep)) {
                        throw new IllegalArgumentException("Unknown dependency: " + dep + " for node: " + n.getName());
                    }
                    outEdges.get(dep).add(n.getName());
                    inDegree.put(n.getName(), inDegree.get(n.getName()) + 1);
                }
            }
        }

        Queue<String> q = new ArrayDeque<>();
        for (var e : inDegree.entrySet()) {
            if (e.getValue() == 0)
                q.add(e.getKey());
        }

        List<GraphDefinition.NodeDef> sorted = new ArrayList<>();
        while (!q.isEmpty()) {
            String curr = q.poll();
            sorted.add(byName.get(curr));
            for (String child : outEdges.get(curr)) {
                int deg = inDegree.get(child) - 1;
                inDegree.put(child, deg);
                if (deg == 0)
                    q.add(child);
            }
        }

        if (sorted.size() != nodes.size()) {
            throw new IllegalStateException("Cycle detected in JSON graph definition");
        }
        return sorted;
    }

    static ScalarCutoff parseCutoff(Map<String, Object> props) {
        Object o = props.get("cutoff");
        if (o == null)
            return ScalarCutoffs.EXACT;
        if (o instanceof String s)
            return switch (s) {
                case "exact" -> ScalarCutoffs.EXACT;
                case "always" -> ScalarCutoffs.ALWAYS;
                case "never" -> ScalarCutoffs.NEVER;
                case "absolute" -> ScalarCutoffs.absoluteTolerance(getDouble(props, "tolerance", 1e-15));
                case "relative" -> ScalarCutoffs.relativeTolerance(getDouble(props, "tolerance", 1e-10));
                default -> throw new IllegalArgumentException("Unknown cutoff: " + s);
            };
        return ScalarCutoffs.EXACT;
    }

    static double getDouble(Map<String, Object> props, String key, double def) {
        Object v = props.get(key);
        if (v == null)
            return def;
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
    }

    static int getInt(Map<String, Object> props, String key, int def) {
        Object v = props.get(key);
        if (v == null)
            return def;
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }

    @FunctionalInterface
    public interface NodeFactory {
        Node<?> create(String name, Map<String, Object> properties, Node<?>[] dependencies);
    }

    /**
     * The result of compilation: a graph engine ready to run.
     */
    private List<GraphDefinition.NodeDef> expandTemplates(List<GraphDefinition.NodeDef> nodes,
            Map<String, GraphDefinition.TemplateDef> templates) {
        List<GraphDefinition.NodeDef> expanded = new ArrayList<>();
        Queue<GraphDefinition.NodeDef> queue = new ArrayDeque<>(nodes);

        while (!queue.isEmpty()) {
            var node = queue.poll();
            if (NodeType.TEMPLATE.name().equalsIgnoreCase(node.getType())) {
                // Expand
                Map<String, Object> state = node.getProperties() != null ? node.getProperties() : Map.of();
                String templateName = (String) state.get("template");
                if (templateName == null)
                    throw new IllegalArgumentException("Template node missing 'template' property: " + node.getName());

                var template = templates.get(templateName);
                if (template == null)
                    throw new IllegalArgumentException("Unknown template: " + templateName);

                Map<String, Object> params = state;

                for (var tNode : template.getNodes()) {
                    var newNode = deepCopy(tNode);
                    // Substitute variables
                    newNode.setName(substitute(newNode.getName(), params));
                    if (newNode.getInputs() != null) {
                        newNode.setInputs(newNode.getInputs().entrySet().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey,
                                        e -> substitute(e.getValue(), params))));
                    }
                    if (newNode.getProperties() != null) {
                        Map<String, Object> resolvedProps = new HashMap<>();
                        for (var entry : newNode.getProperties().entrySet()) {
                            Object val = entry.getValue();
                            if (val instanceof String s && s.contains("{{")) {
                                resolvedProps.put(entry.getKey(), resolveTemplateString(s, params));
                            } else {
                                resolvedProps.put(entry.getKey(), val);
                            }
                        }
                        newNode.setProperties(resolvedProps);
                    }
                    queue.add(newNode); // Add to queue to handle nested templates
                }
            } else {
                expanded.add(node);

                // Vector Auto-Expand Logic
                if (node.getProperties() != null) {
                    Object autoExpandObj = node.getProperties().get("auto_expand");
                    boolean autoExpand = autoExpandObj != null && (autoExpandObj instanceof Boolean b ? b
                            : Boolean.parseBoolean(autoExpandObj.toString()));

                    if (autoExpand) {
                        int size = getInt(node.getProperties(), "size", 0);
                        String prefix = (String) node.getProperties().getOrDefault("auto_expand_prefix", "Elem");
                        List<String> labels = null;
                        Object labelsObj = node.getProperties().get("auto_expand_labels");
                        if (labelsObj == null) {
                            labelsObj = node.getProperties().get("headers");
                        }

                        if (labelsObj instanceof List<?> list) {
                            labels = list.stream().map(Object::toString).toList();
                        }

                        for (int i = 0; i < size; i++) {
                            GraphDefinition.NodeDef elem = new GraphDefinition.NodeDef();

                            String suffix = (labels != null && i < labels.size()) ? labels.get(i) : (prefix + i);
                            elem.setName(node.getName() + "." + suffix);

                            elem.setType("vector_element");
                            elem.setInputs(Map.of("i0", node.getName()));
                            elem.setProperties(Map.of("index", i));
                            expanded.add(elem);
                        }
                    }
                }
            }
        }
        return expanded;
    }

    private String substitute(String s, Map<String, Object> params) {
        if (s == null || !s.contains("{{"))
            return s;
        for (var entry : params.entrySet()) {
            String key = "{{" + entry.getKey() + "}}";
            if (s.contains(key)) {
                s = s.replace(key, String.valueOf(entry.getValue()));
            }
        }
        return s;
    }

    private String resolveTemplateString(String s, Map<String, Object> params) {
        if (s == null || !s.contains("{{"))
            return s;
        for (var entry : params.entrySet()) {
            String key = "{{" + entry.getKey() + "}}";
            if (s.contains(key)) {
                s = s.replace(key, String.valueOf(entry.getValue()));
            }
        }
        return s;
    }

    private GraphDefinition.NodeDef deepCopy(GraphDefinition.NodeDef original) {
        var copy = new GraphDefinition.NodeDef();
        copy.setName(original.getName());
        copy.setType(original.getType());
        copy.setDescription(original.getDescription());
        if (original.getInputs() != null) {
            copy.setInputs(new HashMap<>(original.getInputs()));
        }
        if (original.getProperties() != null) {
            copy.setProperties(new HashMap<>(original.getProperties()));
        }
        return copy;
    }

    /**
     * The result of compilation: a graph engine ready to run.
     */
    public static final class CompiledGraph {
        private final String name, version;
        private final StabilizationEngine engine;
        private final Map<String, Node<?>> nodesByName;
        private final Map<String, String> logicalTypes;
        private final Map<String, String> descriptions;
        private final List<String> originalOrder;
        private final Map<String, Map<String, String>> edgeLabels;

        CompiledGraph(String name, String version, StabilizationEngine engine, Map<String, Node<?>> nodesByName,
                Map<String, String> logicalTypes, Map<String, String> descriptions,
                List<String> originalOrder,
                Map<String, Map<String, String>> edgeLabels) {
            this.name = name;
            this.version = version;
            this.engine = engine;
            this.nodesByName = Collections.unmodifiableMap(nodesByName);
            this.logicalTypes = Collections.unmodifiableMap(logicalTypes);
            this.descriptions = Collections.unmodifiableMap(descriptions);
            this.originalOrder = Collections.unmodifiableList(originalOrder);
            this.edgeLabels = Collections.unmodifiableMap(edgeLabels);
        }

        public String name() {
            return name;
        }

        public String version() {
            return version;
        }

        public StabilizationEngine engine() {
            return engine;
        }

        public Map<String, Node<?>> nodesByName() {
            return nodesByName;
        }

        public Map<String, String> logicalTypes() {
            return logicalTypes;
        }

        public Map<String, String> descriptions() {
            return descriptions;
        }

        public List<String> originalOrder() {
            return originalOrder;
        }

        public Map<String, Map<String, String>> edgeLabels() {
            return edgeLabels;
        }
    }
}
