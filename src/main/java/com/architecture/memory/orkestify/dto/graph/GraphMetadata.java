package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Metadata about the graph visualization.
 * Provides summary information for the UI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphMetadata {

    private int nodeCount;
    private int edgeCount;
    private List<String> nodeTypes;     // List of unique node types in the graph
    private List<String> edgeTypes;     // List of unique edge types in the graph
    private Map<String, Integer> nodeCountByType;  // Count of nodes per type
    private Map<String, Integer> edgeCountByType;  // Count of edges per type
    private String projectId;
    private int depth;                  // Traversal depth used for the query
}
