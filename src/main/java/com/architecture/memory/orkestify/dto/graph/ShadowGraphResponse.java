package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response for shadow graph operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadowGraphResponse {
    private String id;
    private String projectId;
    private String shadowId;
    private String repoUrl;
    private String branchName;
    private String baseBranch;
    private String prNumber;
    private String status;          // PENDING, ANALYZING, COMPLETED, FAILED
    private String errorMessage;    // If FAILED
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime expiresAt;
    private DiffSummary diffSummary;
    private ShadowGraphDiff diff;   // Only populated on detail requests
}
