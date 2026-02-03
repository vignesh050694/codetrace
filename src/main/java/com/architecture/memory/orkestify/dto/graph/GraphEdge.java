package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Represents an edge/relationship in the graph visualization.
 * Compatible with D3.js, Cytoscape.js, and vis.js.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEdge {

    private String id;
    private String source;      // Source node ID
    private String target;      // Target node ID
    private String type;        // CALLS, PRODUCES_TO, CONSUMES_FROM, HAS_ENDPOINT, etc.
    private String label;       // Human-readable label for display
    private Map<String, Object> properties;
    private EdgeStyle style;
}
