package com.architecture.memory.orkestify.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for Graph RAG query
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagQueryRequest {

    /**
     * Natural language question about the codebase
     */
    private String question;

    /**
     * Maximum number of results to retrieve from Qdrant (default: 10)
     */
    @Builder.Default
    private int maxResults = 10;

    /**
     * Whether to include full graph context for each match (default: true)
     */
    @Builder.Default
    private boolean includeGraphContext = true;

    /**
     * Minimum similarity score threshold (0.0 to 1.0, default: 0.7)
     */
    @Builder.Default
    private double minScore = 0.7;
}
