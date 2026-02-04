package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a detected circular dependency in the graph.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CircularDependency {

    public enum Severity {
        WARNING,  // Circular call chain within the same service
        ERROR     // Circular dependency across services/controllers
    }

    private Severity severity;
    private String description;
    private List<String> cycle;          // e.g., ["ServiceA", "ServiceB", "ServiceC", "ServiceA"]
    private List<CycleEdge> cycleEdges;  // Detailed edge information
    private boolean newInShadow;         // true if this cycle doesn't exist in production

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CycleEdge {
        private String fromClass;
        private String fromMethod;
        private String toClass;
        private String toMethod;
        private String relationshipType; // CALLS, HAS_METHOD, etc.
    }
}
