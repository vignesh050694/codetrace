package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Represents a node in the graph visualization.
 * Compatible with D3.js, Cytoscape.js, and vis.js.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphNode {

    private String id;              // UUID for internal use
    private String canonicalId;     // Stable, deterministic ID for cross-branch comparison
    private String label;
    private String type;        // Controller, Service, Endpoint, Method, KafkaTopic, etc.
    private String group;       // For clustering in visualization (e.g., package name, app name)
    private Map<String, Object> properties;
    private NodeStyle style;
}
