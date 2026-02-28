package com.trading.drg.io;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * POJO representation of a Graph Topology.
 *
 * <p>
 * Used for JSON serialization/deserialization. This decouples the runtime graph
 * structure
 * from the persistence format.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class GraphDefinition {
    private GraphInfo graph;

    public GraphInfo getGraph() {
        return graph;
    }

    public void setGraph(GraphInfo graph) {
        this.graph = graph;
    }

    /** Meta-information about the graph. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class GraphInfo {
        private String name, version;
        private long epoch;
        private List<NodeDef> nodes;
        private List<TemplateDef> templates;

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

        public long getEpoch() {
            return epoch;
        }

        public void setEpoch(long epoch) {
            this.epoch = epoch;
        }

        public List<NodeDef> getNodes() {
            return nodes;
        }

        public void setNodes(List<NodeDef> v) {
            nodes = v;
        }

        public List<TemplateDef> getTemplates() {
            return templates;
        }

        public void setTemplates(List<TemplateDef> templates) {
            this.templates = templates;
        }
    }

    /** Definition of a reusable sub-graph template. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TemplateDef {
        private String name;
        private List<NodeDef> nodes;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<NodeDef> getNodes() {
            return nodes;
        }

        public void setNodes(List<NodeDef> nodes) {
            this.nodes = nodes;
        }
    }

    /** Definition of a single node in the graph. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class NodeDef {
        private String name, type, description;
        private Map<String, String> inputs;
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

        public String getDescription() {
            return description;
        }

        public void setDescription(String v) {
            description = v;
        }

        public Map<String, String> getInputs() {
            return inputs;
        }

        public void setInputs(Map<String, String> inputs) {
            this.inputs = inputs;
        }

        public Map<String, Object> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, Object> v) {
            properties = v;
        }
    }
}
