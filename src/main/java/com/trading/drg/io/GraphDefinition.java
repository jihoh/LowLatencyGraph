package com.trading.drg.io;

import java.util.List;
import java.util.Map;

/**
 * POJO representation of a Graph Topology.
 *
 * <p>
 * Used for JSON serialization/deserialization. This decouples the runtime graph
 * structure
 * from the persistence format.
 */
public final class GraphDefinition {
    private GraphInfo graph;

    public GraphInfo getGraph() {
        return graph;
    }

    public void setGraph(GraphInfo graph) {
        this.graph = graph;
    }

    /** Meta-information about the graph. */
    public static final class GraphInfo {
        private String name, version;
        private List<NodeDef> nodes;

        public String getName() {
            return name;
        }

        public void setName(String v) {
            name = v;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String v) {
            version = v;
        }

        public List<NodeDef> getNodes() {
            return nodes;
        }

        public void setNodes(List<NodeDef> v) {
            nodes = v;
        }
    }

    /** Definition of a single node in the graph. */
    public static final class NodeDef {
        private String name, type;
        private boolean source;
        private List<String> dependencies;
        private Map<String, Object> properties;

        public String getName() {
            return name;
        }

        public void setName(String v) {
            name = v;
        }

        public String getType() {
            return type;
        }

        public void setType(String v) {
            type = v;
        }

        public boolean isSource() {
            return source;
        }

        public void setSource(boolean v) {
            source = v;
        }

        public List<String> getDependencies() {
            return dependencies;
        }

        public void setDependencies(List<String> v) {
            dependencies = v;
        }

        public Map<String, Object> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, Object> v) {
            properties = v;
        }
    }
}
