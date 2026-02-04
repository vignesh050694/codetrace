package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Represents a single node change between production and shadow graphs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeChange {

    public enum ChangeType {
        ADDED,
        MODIFIED,
        REMOVED
    }

    private ChangeType changeType;
    private String nodeType;        // Controller, Service, Endpoint, Method, etc.
    private String nodeKey;         // Semantic key (e.g., "com.example.UserService")
    private String displayName;     // Human-readable name

    // For MODIFIED nodes: what changed
    private List<PropertyDiff> propertyDiffs;

    // Snapshot of node properties
    private Map<String, Object> productionProperties;
    private Map<String, Object> shadowProperties;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PropertyDiff {
        private String property;
        private Object oldValue;
        private Object newValue;
    }
}
