package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for node graph by ID, including both the graph and the IDE-style narrative.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeGraphByIdResponse {
    private GraphVisualizationResponse graph;
    private String narrative;
}
