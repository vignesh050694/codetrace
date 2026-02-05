package com.architecture.memory.orkestify.controller;

import com.architecture.memory.orkestify.dto.graph.CreateShadowGraphRequest;
import com.architecture.memory.orkestify.dto.graph.ShadowGraphResponse;
import com.architecture.memory.orkestify.exception.ProjectNotFoundException;
import com.architecture.memory.orkestify.exception.UserNotFoundException;
import com.architecture.memory.orkestify.model.User;
import com.architecture.memory.orkestify.repository.ProjectRepository;
import com.architecture.memory.orkestify.repository.UserRepository;
import com.architecture.memory.orkestify.service.graph.ShadowGraphService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for shadow graph operations.
 * Shadow graphs represent PR branch code analysis compared against the production graph.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/graph/shadow")
@RequiredArgsConstructor
@Slf4j
public class ShadowGraphController {

    private final ShadowGraphService shadowGraphService;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    /**
     * Create a shadow graph for a PR branch.
     * Returns immediately with PENDING status; processing happens asynchronously.
     */
    @PostMapping
    public ResponseEntity<ShadowGraphResponse> createShadowGraph(
            @PathVariable String projectId,
            @Valid @RequestBody CreateShadowGraphRequest request) {
        log.info("Creating shadow graph for project: {}, branch: {}", projectId, request.getBranchName());
        validateProjectAccess(projectId);

        ShadowGraphResponse response = shadowGraphService.createShadowGraph(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * List all shadow graphs for a project.
     */
    @GetMapping
    public ResponseEntity<List<ShadowGraphResponse>> listShadowGraphs(@PathVariable String projectId) {
        log.info("Listing shadow graphs for project: {}", projectId);
        validateProjectAccess(projectId);

        List<ShadowGraphResponse> responses = shadowGraphService.listShadowGraphs(projectId);
        return ResponseEntity.ok(responses);
    }

    /**
     * Get shadow graph status and summary.
     */
    @GetMapping("/{shadowId}")
    public ResponseEntity<ShadowGraphResponse> getShadowGraph(
            @PathVariable String projectId,
            @PathVariable String shadowId) {
        log.info("Getting shadow graph: {}/{}", projectId, shadowId);
        validateProjectAccess(projectId);

        ShadowGraphResponse response = shadowGraphService.getShadowGraph(projectId, shadowId, false);
        return ResponseEntity.ok(response);
    }

    /**
     * Get shadow graph with full diff details.
     */
    @GetMapping("/{shadowId}/diff")
    public ResponseEntity<ShadowGraphResponse> getShadowGraphDiff(
            @PathVariable String projectId,
            @PathVariable String shadowId) {
        log.info("Getting shadow graph diff: {}/{}", projectId, shadowId);
        validateProjectAccess(projectId);

        ShadowGraphResponse response = shadowGraphService.getShadowGraph(projectId, shadowId, true);
        return ResponseEntity.ok(response);
    }

    /**
     * Delete a shadow graph and its associated Neo4j nodes.
     */
    @DeleteMapping("/{shadowId}")
    public ResponseEntity<Void> deleteShadowGraph(
            @PathVariable String projectId,
            @PathVariable String shadowId) {
        log.info("Deleting shadow graph: {}/{}", projectId, shadowId);
        validateProjectAccess(projectId);

        shadowGraphService.deleteShadowGraph(projectId, shadowId);
        return ResponseEntity.noContent().build();
    }

    private void validateProjectAccess(String projectId) {
        String username = getAuthenticatedUsername();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (!projectRepository.existsByIdAndUserId(projectId, user.getId())) {
            throw new ProjectNotFoundException("Project not found with id: " + projectId);
        }
    }

    private String getAuthenticatedUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication.getName();
    }
}
