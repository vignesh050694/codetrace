package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to create a shadow graph from a PR branch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateShadowGraphRequest {
    private String repoUrl;         // GitHub repository URL
    private String branchName;      // PR branch name (e.g., "feature/add-user-service")
    private String baseBranch;      // Base branch (e.g., "main", "master") - optional, defaults to "main"
    private String prNumber;        // Pull request number - optional, for reference
}
