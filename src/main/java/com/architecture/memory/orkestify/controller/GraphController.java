package com.architecture.memory.orkestify.controller;

import com.architecture.memory.orkestify.dto.graph.*;
import com.architecture.memory.orkestify.exception.ProjectNotFoundException;
import com.architecture.memory.orkestify.exception.UserNotFoundException;
import com.architecture.memory.orkestify.model.User;
import com.architecture.memory.orkestify.repository.ProjectRepository;
import com.architecture.memory.orkestify.repository.UserRepository;
import com.architecture.memory.orkestify.service.graph.GraphQueryService;
import com.architecture.memory.orkestify.service.graph.GraphVisualizationService;
import com.architecture.memory.orkestify.service.graph.HierarchyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
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
    private final HierarchyService hierarchyService;
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

    // ==================== Node Query APIs ====================

    /**
     * Get all nodes of a specific type.
     * Returns only nodes (no edges) for the specified type.
     *
     * @param projectId The project ID
     * @param type Node type: Application, Controller, Endpoint, Service, Method, Repository,
     *             DatabaseTable, KafkaTopic, KafkaListener
     */
    @Operation(summary = "Get nodes by type",
               description = "Returns all nodes of a specific type in the project. ")
    @GetMapping("/nodes")
    public ResponseEntity<NodeListResponse> getNodesByType(
            @PathVariable String projectId,
            @Parameter(description = "Node type to filter by", required = true,
                       example = "SERVICE")
            @RequestParam String type) {
        log.info("[GraphController] Getting nodes by type - project: {}, type: {}", projectId, type);

        try {
            validateProjectAccess(projectId);
            log.debug("[GraphController] Project access validated");

            log.debug("[GraphController] Calling graphVisualizationService.getNodesByType...");
            NodeListResponse response = graphVisualizationService.getNodesByType(projectId, type);

            log.info("[GraphController] Successfully retrieved {} nodes of type {}",
                    response.getTotalCount(), type);
            return ResponseEntity.ok(response);

        } catch (StackOverflowError soe) {
            log.error("[GraphController] StackOverflowError while getting nodes - project: {}, type: {}. " +
                    "This is likely caused by circular reference in entity relationships (e.g., Endpoint -> ExternalCall -> Endpoint). " +
                    "Check @JsonIgnore annotations on bidirectional relationships.",
                    projectId, type, soe);
            throw new RuntimeException("StackOverflowError: Circular reference detected when loading " + type + " nodes. " +
                    "Please check entity relationship annotations.", soe);
        } catch (Exception e) {
            log.error("[GraphController] Error getting nodes by type - project: {}, type: {}, error: {}",
                    projectId, type, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Get a node by ID with complete depth traversal for visualization.
     * Returns the specified node along with ALL downstream dependencies recursively.
     *
     * @param projectId The project ID
     * @param nodeId The unique ID of the node
     */
    @Operation(summary = "Get node graph by ID",
               description = "Returns the specified node with complete depth traversal - all downstream " +
                           "dependencies are included recursively. For example, an Endpoint will include " +
                           "all called Methods, their called Methods, External calls, Kafka topics, etc.")
    @GetMapping("/nodes/{nodeId}")
    public ResponseEntity<NodeGraphByIdResponse> getNodeGraphById(
            @PathVariable String projectId,
            @Parameter(description = "The unique ID of the node", required = true)
            @PathVariable String nodeId) {
        log.info("Getting node graph by ID for project: {}, nodeId: {}", projectId, nodeId);

        try {
            validateProjectAccess(projectId);
            log.debug("Project access validated for projectId: {}", projectId);

            log.debug("Fetching node graph from visualization service...");
            GraphVisualizationResponse response = graphVisualizationService.getNodeGraphById(projectId, nodeId);

            if (response == null) {
                log.warn("Node graph returned null for projectId: {}, nodeId: {}", projectId, nodeId);
                return ResponseEntity.noContent().build();
            }

            // Load template and populate narrative
            String template = new String(Files.readAllBytes(Paths.get("src/main/resources/ide-narrative-template.txt")));
            String narrative = NarrativeTemplatePopulator.populate(template, response);

            NodeGraphByIdResponse result = NodeGraphByIdResponse.builder()
                    .graph(response)
                    .narrative(narrative)
                    .build();

            log.info("Successfully retrieved node graph for projectId: {}, nodeId: {} with {} nodes and {} edges",
                    projectId, nodeId,
                    response.getNodes() != null ? response.getNodes().size() : 0,
                    response.getEdges() != null ? response.getEdges().size() : 0);

            return ResponseEntity.ok(result);

        }  catch (Exception e) {
            log.error("[Graph/Nodes] Unexpected error occurred - projectId: {}, nodeId: {}", projectId, nodeId, e);
            log.error("[Graph/Nodes] Exception type: {}, Message: {}", e.getClass().getName(), e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }

    // ==================== Hierarchy Drill-Down API ====================

    /**
     * Level 1: Get list of all applications with summary counts.
     * Base endpoint for hierarchical exploration.
     */
    @GetMapping("/hierarchy/applications")
    public ResponseEntity<ApplicationListResponse> getHierarchyApplications(@PathVariable String projectId) {
        log.info("Getting hierarchy applications for project: {}", projectId);
        validateProjectAccess(projectId);

        ApplicationListResponse response = hierarchyService.getApplications(projectId);
        return ResponseEntity.ok(response);
    }

    /**
     * Level 2: Get application details with all components.
     * Shows controllers, services, Kafka producers/consumers, and database tables.
     */
    @GetMapping("/hierarchy/applications/{appId}")
    public ResponseEntity<ApplicationDetailResponse> getHierarchyApplicationDetail(
            @PathVariable String projectId,
            @PathVariable String appId) {
        log.info("Getting hierarchy application detail for project: {}, appId: {}", projectId, appId);
        validateProjectAccess(projectId);

        ApplicationDetailResponse response = hierarchyService.getApplicationDetail(projectId, appId);
        return ResponseEntity.ok(response);
    }

    /**
     * Level 3: Get controller details with endpoints and internal flow.
     * Shows service calls, Kafka produces, and external calls for each endpoint.
     */
    @GetMapping("/hierarchy/controllers/{controllerId}")
    public ResponseEntity<ControllerDetailResponse> getHierarchyControllerDetail(
            @PathVariable String projectId,
            @PathVariable String controllerId) {
        log.info("Getting hierarchy controller detail for project: {}, controllerId: {}", projectId, controllerId);
        validateProjectAccess(projectId);

        ControllerDetailResponse response = hierarchyService.getControllerDetail(projectId, controllerId);
        return ResponseEntity.ok(response);
    }

    /**
     * Level 3: Get service details with callers and methods.
     * Shows who calls this service and what it calls.
     */
    @GetMapping("/hierarchy/services/{serviceId}")
    public ResponseEntity<ServiceDetailResponse> getHierarchyServiceDetail(
            @PathVariable String projectId,
            @PathVariable String serviceId) {
        log.info("Getting hierarchy service detail for project: {}, serviceId: {}", projectId, serviceId);
        validateProjectAccess(projectId);

        ServiceDetailResponse response = hierarchyService.getServiceDetail(projectId, serviceId);
        return ResponseEntity.ok(response);
    }

    /**
     * Level 3: Get endpoint internal flow with call tree.
     * Shows the full call tree, Kafka interactions, external calls, and database access.
     */
    @GetMapping("/hierarchy/endpoints/{endpointId}")
    public ResponseEntity<EndpointFlowResponse> getHierarchyEndpointFlow(
            @PathVariable String projectId,
            @PathVariable String endpointId) {
        log.info("Getting hierarchy endpoint flow for project: {}, endpointId: {}", projectId, endpointId);
        validateProjectAccess(projectId);

        EndpointFlowResponse response = hierarchyService.getEndpointFlow(projectId, endpointId);
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
