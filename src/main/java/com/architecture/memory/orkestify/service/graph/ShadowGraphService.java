package com.architecture.memory.orkestify.service.graph;

import com.architecture.memory.orkestify.dto.CodeAnalysisResponse;
import com.architecture.memory.orkestify.dto.graph.*;
import com.architecture.memory.orkestify.model.ShadowGraph;
import com.architecture.memory.orkestify.repository.ShadowGraphRepository;
import com.architecture.memory.orkestify.service.CodeAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates shadow graph creation and diff computation.
 *
 * Flow:
 * 1. Clone the PR branch repository
 * 2. Analyze the code with Spoon (same pipeline as production)
 * 3. Persist the analysis with a shadow project ID (isolated in Neo4j)
 * 4. Compute diff between production and shadow graphs
 * 5. Detect circular dependencies
 * 6. Store results in MongoDB
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ShadowGraphService {

    private final ShadowGraphRepository shadowGraphRepository;
    private final CodeAnalysisService codeAnalysisService;
    private final GraphPersistenceService graphPersistenceService;
    private final GraphDiffService graphDiffService;
    private final CircularDependencyDetector circularDependencyDetector;

    private static final int SHADOW_GRAPH_TTL_HOURS = 24;

    /**
     * Create a shadow graph for a PR branch. Returns immediately with PENDING status,
     * then processes asynchronously.
     */
    public ShadowGraphResponse createShadowGraph(String projectId, CreateShadowGraphRequest request) {
        String shadowId = UUID.randomUUID().toString().substring(0, 8);
        String baseBranch = request.getBaseBranch() != null ? request.getBaseBranch() : "main";

        ShadowGraph shadowGraph = ShadowGraph.builder()
                .projectId(projectId)
                .shadowId(shadowId)
                .repoUrl(request.getRepoUrl())
                .branchName(request.getBranchName())
                .baseBranch(baseBranch)
                .prNumber(request.getPrNumber())
                .status(ShadowGraph.Status.PENDING)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(SHADOW_GRAPH_TTL_HOURS))
                .build();

        shadowGraphRepository.save(shadowGraph);
        log.info("Created shadow graph {} for project {} branch {}", shadowId, projectId, request.getBranchName());

        // Trigger async processing
        processAsync(projectId, shadowId);

        return toResponse(shadowGraph, false);
    }

    /**
     * Async processing: clone, analyze, persist, diff, detect cycles.
     */
    @Async
    public void processAsync(String projectId, String shadowId) {
        ShadowGraph shadowGraph = shadowGraphRepository.findByProjectIdAndShadowId(projectId, shadowId)
                .orElse(null);
        if (shadowGraph == null) {
            log.error("Shadow graph not found: {}/{}", projectId, shadowId);
            return;
        }

        try {
            // Mark as analyzing
            shadowGraph.setStatus(ShadowGraph.Status.ANALYZING);
            shadowGraphRepository.save(shadowGraph);

            String shadowProjectId = shadowGraph.getShadowProjectId();

            // Step 1: Analyze the PR branch
            log.info("Analyzing PR branch: {} for shadow {}", shadowGraph.getBranchName(), shadowId);
            List<CodeAnalysisResponse> analyses = codeAnalysisService.analyzeRepositoryBranch(
                    shadowProjectId, shadowGraph.getRepoUrl(), shadowGraph.getBranchName());

            // Step 2: Persist to Neo4j with shadow project ID
            log.info("Persisting shadow graph {} ({} applications)", shadowId, analyses.size());
            for (CodeAnalysisResponse analysis : analyses) {
                graphPersistenceService.persistAnalysis(
                        shadowProjectId, "shadow", shadowGraph.getRepoUrl(), analysis);
            }

            // Step 3: Compute diff
            log.info("Computing diff for shadow {}", shadowId);
            ShadowGraphDiff diff = graphDiffService.computeDiff(projectId, shadowProjectId);

            // Step 4: Detect circular dependencies
            log.info("Detecting circular dependencies for shadow {}", shadowId);
            List<CircularDependency> circularDeps = circularDependencyDetector.detectAndCompare(
                    projectId, shadowProjectId);
            diff.setCircularDependencies(circularDeps);

            // Update summary with circular dependency count
            if (diff.getSummary() != null) {
                diff.getSummary().setCircularDependenciesDetected(circularDeps.size());
            }

            // Step 5: Save results
            shadowGraph.setDiff(diff);
            shadowGraph.setCircularDependencies(circularDeps);
            shadowGraph.setStatus(ShadowGraph.Status.COMPLETED);
            shadowGraph.setCompletedAt(LocalDateTime.now());
            shadowGraphRepository.save(shadowGraph);

            log.info("Shadow graph {} completed. Changes: {} added, {} modified, {} removed, {} circular deps",
                    shadowId,
                    diff.getSummary().getNodesAdded(),
                    diff.getSummary().getNodesModified(),
                    diff.getSummary().getNodesRemoved(),
                    circularDeps.size());

        } catch (Exception e) {
            log.error("Shadow graph processing failed for {}: {}", shadowId, e.getMessage(), e);
            shadowGraph.setStatus(ShadowGraph.Status.FAILED);
            shadowGraph.setErrorMessage(e.getMessage());
            shadowGraph.setCompletedAt(LocalDateTime.now());
            shadowGraphRepository.save(shadowGraph);
        }
    }

    /**
     * Get a shadow graph by ID with optional full diff.
     */
    public ShadowGraphResponse getShadowGraph(String projectId, String shadowId, boolean includeDiff) {
        ShadowGraph shadowGraph = shadowGraphRepository.findByProjectIdAndShadowId(projectId, shadowId)
                .orElseThrow(() -> new RuntimeException("Shadow graph not found: " + shadowId));
        return toResponse(shadowGraph, includeDiff);
    }

    /**
     * List all shadow graphs for a project.
     */
    public List<ShadowGraphResponse> listShadowGraphs(String projectId) {
        return shadowGraphRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(sg -> toResponse(sg, false))
                .toList();
    }

    /**
     * Delete a shadow graph and its Neo4j nodes.
     */
    public void deleteShadowGraph(String projectId, String shadowId) {
        ShadowGraph shadowGraph = shadowGraphRepository.findByProjectIdAndShadowId(projectId, shadowId)
                .orElseThrow(() -> new RuntimeException("Shadow graph not found: " + shadowId));

        // Delete Neo4j nodes
        String shadowProjectId = shadowGraph.getShadowProjectId();
        graphPersistenceService.deleteProjectGraph(shadowProjectId);

        // Delete MongoDB record
        shadowGraphRepository.deleteByProjectIdAndShadowId(projectId, shadowId);

        log.info("Deleted shadow graph {} for project {}", shadowId, projectId);
    }

    /**
     * Clean up expired shadow graphs.
     */
    public int cleanupExpiredShadowGraphs() {
        List<ShadowGraph> expired = shadowGraphRepository.findByExpiresAtBefore(LocalDateTime.now());
        int count = 0;

        for (ShadowGraph sg : expired) {
            try {
                String shadowProjectId = sg.getShadowProjectId();
                graphPersistenceService.deleteProjectGraph(shadowProjectId);
                shadowGraphRepository.delete(sg);
                count++;
                log.info("Cleaned up expired shadow graph: {}/{}", sg.getProjectId(), sg.getShadowId());
            } catch (Exception e) {
                log.error("Failed to cleanup shadow graph {}: {}", sg.getShadowId(), e.getMessage());
            }
        }

        if (count > 0) {
            log.info("Cleaned up {} expired shadow graphs", count);
        }
        return count;
    }

    // ========================= RESPONSE MAPPING =========================

    private ShadowGraphResponse toResponse(ShadowGraph sg, boolean includeDiff) {
        ShadowGraphResponse.ShadowGraphResponseBuilder builder = ShadowGraphResponse.builder()
                .id(sg.getId())
                .projectId(sg.getProjectId())
                .shadowId(sg.getShadowId())
                .repoUrl(sg.getRepoUrl())
                .branchName(sg.getBranchName())
                .baseBranch(sg.getBaseBranch())
                .prNumber(sg.getPrNumber())
                .status(sg.getStatus())
                .errorMessage(sg.getErrorMessage())
                .createdAt(sg.getCreatedAt())
                .completedAt(sg.getCompletedAt())
                .expiresAt(sg.getExpiresAt());

        if (sg.getDiff() != null) {
            builder.diffSummary(sg.getDiff().getSummary());
            if (includeDiff) {
                builder.diff(sg.getDiff());
            }
        }

        return builder.build();
    }
}
