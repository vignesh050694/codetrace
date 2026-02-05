package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Summary statistics for a shadow graph diff.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffSummary {
    private int totalChanges;
    private int nodesAdded;
    private int nodesModified;
    private int nodesRemoved;
    private int relationshipsAdded;
    private int relationshipsRemoved;
    private int circularDependenciesDetected;

    // Breakdown by node type: e.g., {"Controller": 2, "Endpoint": 5, "Service": 1}
    private Map<String, Integer> addedByType;
    private Map<String, Integer> modifiedByType;
    private Map<String, Integer> removedByType;
}
