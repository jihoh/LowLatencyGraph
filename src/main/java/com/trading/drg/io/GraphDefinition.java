package com.trading.drg.io;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

/**
 * POJO representation of a Graph Topology.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public final class GraphDefinition {
    private GraphInfo graph;

    /** Meta-information about the graph. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class GraphInfo {
        private String name, version;
        private long epoch;
        private List<NodeDef> nodes;
        private List<TemplateDef> templates;
    }

    /** Definition of a reusable sub-graph template. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TemplateDef {
        private String name;
        private List<NodeDef> nodes;
    }

    /** Definition of a single node in the graph. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class NodeDef {
        private String name, type, description;
        private Map<String, String> inputs;
        private Map<String, Object> properties;
    }
}
