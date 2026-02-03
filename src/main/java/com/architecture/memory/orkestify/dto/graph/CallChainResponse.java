package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for method call chain trace.
 * Shows the flow of method calls from an endpoint or method.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallChainResponse {

    private GraphNode root;                     // Starting point (endpoint or method)
    private List<CallChainEntry> callChain;     // Flat list of call relationships
    private GraphVisualizationResponse visualizationFormat;  // Graph format for visualization
    private int maxDepth;
    private String direction;                   // "callees" or "callers"

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallChainEntry {
        private int depth;
        private String callerId;
        private String callerLabel;
        private String callerType;
        private String calleeId;
        private String calleeLabel;
        private String calleeType;
        private Integer lineNumber;
        private String relationshipType;        // CALLS, MAKES_EXTERNAL_CALL, etc.
    }
}
