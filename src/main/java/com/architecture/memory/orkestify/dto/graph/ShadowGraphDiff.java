package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Complete diff between a production graph and a shadow (PR branch) graph.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadowGraphDiff {
    private List<NodeChange> addedNodes;
    private List<NodeChange> modifiedNodes;
    private List<NodeChange> removedNodes;
    private List<RelationshipChange> addedRelationships;
    private List<RelationshipChange> removedRelationships;
    private List<CircularDependency> circularDependencies;
    private DiffSummary summary;
}
