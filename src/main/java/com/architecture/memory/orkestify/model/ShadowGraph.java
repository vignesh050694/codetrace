package com.architecture.memory.orkestify.model;

import com.architecture.memory.orkestify.dto.graph.CircularDependency;
import com.architecture.memory.orkestify.dto.graph.ShadowGraphDiff;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MongoDB document tracking shadow graph state and results.
 * A shadow graph represents the code analysis of a PR branch,
 * compared against the production (main branch) graph.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shadow_graphs")
@CompoundIndex(name = "project_shadow_idx", def = "{'projectId': 1, 'shadowId': 1}", unique = true)
public class ShadowGraph {

    @Id
    private String id;

    @Indexed
    private String projectId;

    private String shadowId;        // Unique identifier for this shadow graph
    private String repoUrl;
    private String branchName;      // PR branch
    private String baseBranch;      // Base branch (main/master)
    private String prNumber;        // PR number (optional, for reference)

    private String status;          // PENDING, ANALYZING, COMPLETED, FAILED
    private String errorMessage;    // Error details if FAILED

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    @Indexed
    private LocalDateTime expiresAt; // For cleanup scheduler

    // The computed diff between production and shadow
    private ShadowGraphDiff diff;

    // Circular dependencies detected in the shadow graph
    private List<CircularDependency> circularDependencies;

    /**
     * Build the shadow project ID used for Neo4j node isolation.
     * Format: {projectId}::shadow::{shadowId}
     */
    public String getShadowProjectId() {
        return projectId + "::shadow::" + shadowId;
    }

    public static class Status {
        public static final String PENDING = "PENDING";
        public static final String ANALYZING = "ANALYZING";
        public static final String COMPLETED = "COMPLETED";
        public static final String FAILED = "FAILED";
    }
}
