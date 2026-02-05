package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for listing nodes by type.
 * Contains a list of nodes and metadata about the query.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeListResponse {

    private List<GraphNode> nodes;
    private String nodeType;
    private int totalCount;
    private String projectId;
}
