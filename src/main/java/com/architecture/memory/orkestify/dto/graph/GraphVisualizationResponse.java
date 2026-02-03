package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Main response DTO for graph visualization.
 * Compatible with D3.js, Cytoscape.js, and vis.js graph libraries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphVisualizationResponse {

    private List<GraphNode> nodes;
    private List<GraphEdge> edges;
    private GraphMetadata metadata;
}
