package com.architecture.memory.orkestify.controller;

import com.architecture.memory.orkestify.dto.graph.*;
import com.architecture.memory.orkestify.exception.ProjectNotFoundException;
import com.architecture.memory.orkestify.exception.UserNotFoundException;
import com.architecture.memory.orkestify.model.User;
import com.architecture.memory.orkestify.repository.ProjectRepository;
import com.architecture.memory.orkestify.repository.UserRepository;
import com.architecture.memory.orkestify.service.graph.GraphQueryService;
import com.architecture.memory.orkestify.service.graph.GraphVisualizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for graph visualization queries.
 * Provides endpoints for querying the Neo4j code architecture graph.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/graph")
@RequiredArgsConstructor
@Slf4j
public class GraphController {

    private final GraphQueryService graphQueryService;
    private final GraphVisualizationService graphVisualizationService;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    /**
     * Get architecture overview/summary for a project.
     * Returns counts and statistics about the codebase.
     */
    @GetMapping("/overview")
    public ResponseEntity<ArchitectureSummaryResponse> getArchitectureOverview(@PathVariable String projectId) {
        log.info("Getting architecture overview for project: {}", projectId);
        validateProjectAccess(projectId);

        ArchitectureSummaryResponse response = graphVisualizationService.buildArchitectureSummary(projectId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get full graph visualization for a project.
     * Returns nodes and edges in D3.js/Cytoscape compatible format.
     *
     * @param projectId The project ID
     * @param depth Maximum traversal depth (default: 3)
     * @param nodeTypes Optional list of node types to include (e.g., Controller, Service, Endpoint)
     */
    @GetMapping("/visualization")
    public ResponseEntity<GraphVisualizationResponse> getFullGraphVisualization(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "3") int depth,
            @RequestParam(required = false) List<String> nodeTypes) {
        log.info("Getting full graph visualization for project: {}, depth: {}, nodeTypes: {}", projectId, depth, nodeTypes);
        validateProjectAccess(projectId);

        GraphVisualizationResponse response = graphVisualizationService.buildFullGraphVisualization(projectId, depth, nodeTypes);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all applications in a project.
     */
    @GetMapping("/applications")
    public ResponseEntity<GraphVisualizationResponse> getApplicationsGraph(@PathVariable String projectId) {
        log.info("Getting applications graph for project: {}", projectId);
        validateProjectAccess(projectId);

        // Build a simple application-level visualization
        GraphVisualizationResponse response = graphVisualizationService.buildFullGraphVisualization(projectId, 1, List.of("Application"));
        return ResponseEntity.ok(response);
    }

    /**
     * Get endpoints graph with optional filtering.
     *
     * @param projectId The project ID
     * @param method Optional HTTP method filter (GET, POST, PUT, DELETE, etc.)
     * @param pathPattern Optional path pattern filter (supports * wildcard)
     */
    @GetMapping("/endpoints")
    public ResponseEntity<GraphVisualizationResponse> getEndpointsGraph(
            @PathVariable String projectId,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String pathPattern) {
        log.info("Getting endpoints graph for project: {}, method: {}, pathPattern: {}", projectId, method, pathPattern);
        validateProjectAccess(projectId);

        GraphVisualizationResponse response = graphVisualizationService.buildEndpointsGraph(projectId, method, pathPattern);
        return ResponseEntity.ok(response);
    }

    /**
     * Get service dependency graph.
     * Shows which services depend on which other services.
     */
    @GetMapping("/services/dependencies")
    public ResponseEntity<GraphVisualizationResponse> getServiceDependencies(@PathVariable String projectId) {
        log.info("Getting service dependencies for project: {}", projectId);
        validateProjectAccess(projectId);

        GraphVisualizationResponse response = graphVisualizationService.buildServiceDependencyGraph(projectId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get Kafka flow visualization.
     * Shows producers → topics → consumers flow.
     */
    @GetMapping("/kafka/flows")
    public ResponseEntity<GraphVisualizationResponse> getKafkaFlows(@PathVariable String projectId) {
        log.info("Getting Kafka flows for project: {}", projectId);
        validateProjectAccess(projectId);

        GraphVisualizationResponse response = graphVisualizationService.buildKafkaFlowGraph(projectId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get call chain trace from an endpoint or method.
     *
     * @param projectId The project ID
     * @param from Starting node ID (endpoint or method)
     * @param method Alternative: method signature to start from
     * @param maxDepth Maximum call chain depth (default: 5)
     * @param direction "callees" (default) or "callers"
     */
    @GetMapping("/call-chain")
    public ResponseEntity<CallChainResponse> getCallChain(
            @PathVariable String projectId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String method,
            @RequestParam(defaultValue = "5") int maxDepth,
            @RequestParam(defaultValue = "callees") String direction) {
        log.info("Getting call chain for project: {}, from: {}, method: {}, maxDepth: {}, direction: {}",
                projectId, from, method, maxDepth, direction);
        validateProjectAccess(projectId);

        String startNodeId = from != null ? from : method;
        if (startNodeId == null) {
            return ResponseEntity.badRequest().build();
        }

        CallChainResponse response = graphVisualizationService.buildCallChainVisualization(projectId, startNodeId, maxDepth, direction);
        return ResponseEntity.ok(response);
    }

    /**
     * Get external API calls graph.
     *
     * @param projectId The project ID
     * @param resolved Optional filter: true for resolved only, false for unresolved only
     */
    @GetMapping("/external-calls")
    public ResponseEntity<GraphVisualizationResponse> getExternalCalls(
            @PathVariable String projectId,
            @RequestParam(required = false) Boolean resolved) {
        log.info("Getting external calls for project: {}, resolved: {}", projectId, resolved);
        validateProjectAccess(projectId);

        GraphVisualizationResponse response = graphVisualizationService.buildExternalCallsGraph(projectId, resolved);
        return ResponseEntity.ok(response);
    }

    /**
     * Get database access graph.
     * Shows which repositories access which tables.
     */
    @GetMapping("/database/access")
    public ResponseEntity<GraphVisualizationResponse> getDatabaseAccessGraph(@PathVariable String projectId) {
        log.info("Getting database access graph for project: {}", projectId);
        validateProjectAccess(projectId);

        GraphVisualizationResponse response = graphVisualizationService.buildDatabaseAccessGraph(projectId);
        return ResponseEntity.ok(response);
    }

    /**
     * Validate that the current user has access to the project.
     */
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
