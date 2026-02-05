package com.architecture.memory.orkestify.service.graph;

import com.architecture.memory.orkestify.dto.graph.*;
import com.architecture.memory.orkestify.model.graph.nodes.*;
import com.architecture.memory.orkestify.repository.graph.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for converting Neo4j graph data to visualization-friendly DTOs.
 * Produces output compatible with D3.js, Cytoscape.js, and vis.js.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GraphVisualizationService {

    private final ApplicationNodeRepository applicationNodeRepository;
    private final ControllerNodeRepository controllerNodeRepository;
    private final EndpointNodeRepository endpointNodeRepository;
    private final ServiceNodeRepository serviceNodeRepository;
    private final MethodNodeRepository methodNodeRepository;
    private final RepositoryClassNodeRepository repositoryClassNodeRepository;
    private final KafkaTopicNodeRepository kafkaTopicNodeRepository;
    private final KafkaListenerNodeRepository kafkaListenerNodeRepository;
    private final Neo4jClient neo4jClient;

    // Node type colors for visualization
    private static final Map<String, String> NODE_COLORS = Map.of(
            "Application", "#2196F3",
            "Controller", "#4CAF50",
            "Endpoint", "#8BC34A",
            "Service", "#FF9800",
            "Method", "#FFC107",
            "Repository", "#9C27B0",
            "DatabaseTable", "#673AB7",
            "KafkaTopic", "#E91E63",
            "KafkaListener", "#F44336",
            "Configuration", "#607D8B"
    );

    // Edge type colors for visualization
    private static final Map<String, String> EDGE_COLORS = Map.of(
            "CONTAINS_CONTROLLER", "#4CAF50",
            "HAS_ENDPOINT", "#8BC34A",
            "CONTAINS_SERVICE", "#FF9800",
            "HAS_METHOD", "#FFC107",
            "CALLS", "#2196F3",
            "MAKES_EXTERNAL_CALL", "#F44336",
            "PRODUCES_TO", "#E91E63",
            "CONSUMES_FROM", "#9C27B0",
            "ACCESSES", "#673AB7"
    );

    /**
     * Build architecture summary for a project.
     */
    public ArchitectureSummaryResponse buildArchitectureSummary(String projectId) {
        List<ApplicationNode> apps = applicationNodeRepository.findByProjectId(projectId);
        List<ControllerNode> controllers = controllerNodeRepository.findByProjectId(projectId);
        List<EndpointNode> endpoints = endpointNodeRepository.findByProjectId(projectId);
        List<ServiceNode> services = serviceNodeRepository.findByProjectId(projectId);
        List<RepositoryClassNode> repositories = repositoryClassNodeRepository.findByProjectIdWithDatabaseTables(projectId);
        List<KafkaTopicNode> topics = kafkaTopicNodeRepository.findByProjectId(projectId);
        List<KafkaListenerNode> listeners = kafkaListenerNodeRepository.findByProjectId(projectId);

        // Count database tables from repositories
        long databaseTablesCount = repositories.stream()
                .filter(r -> r.getAccessesTables() != null && !r.getAccessesTables().isEmpty())
                .count();

        // Count configurations - get from apps
        long configurationsCount = apps.stream()
                .mapToLong(app -> app.getConfigurations() != null ? app.getConfigurations().size() : 0)
                .sum();

        // Count endpoints by HTTP method
        Map<String, Long> endpointsByMethod = endpoints.stream()
                .filter(e -> e.getHttpMethod() != null)
                .collect(Collectors.groupingBy(EndpointNode::getHttpMethod, Collectors.counting()));

        // Count repositories by type
        Map<String, Long> repositoriesByType = repositories.stream()
                .filter(r -> r.getRepositoryType() != null)
                .collect(Collectors.groupingBy(RepositoryClassNode::getRepositoryType, Collectors.counting()));

        // Build application summaries - count components per app
        List<ArchitectureSummaryResponse.ApplicationSummary> appSummaries = apps.stream()
                .map(app -> {
                    String appKey = app.getAppKey();
                    int appControllers = (int) controllers.stream().filter(c -> appKey.equals(c.getAppKey())).count();
                    int appServices = (int) services.stream().filter(s -> appKey.equals(s.getAppKey())).count();
                    int appRepositories = (int) repositories.stream().filter(r -> appKey.equals(r.getAppKey())).count();
                    int appKafkaListeners = (int) listeners.stream().filter(l -> appKey.equals(l.getAppKey())).count();

                    return ArchitectureSummaryResponse.ApplicationSummary.builder()
                            .id(app.getId())
                            .appKey(app.getAppKey())
                            .mainClassName(app.getMainClassName())
                            .packageName(app.getMainClassPackage())
                            .repoUrl(app.getRepoUrl())
                            .isSpringBoot(app.isSpringBoot())
                            .controllersCount(appControllers)
                            .servicesCount(appServices)
                            .repositoriesCount(appRepositories)
                            .kafkaListenersCount(appKafkaListeners)
                            .configurationsCount(app.getConfigurations() != null ? app.getConfigurations().size() : 0)
                            .build();
                })
                .collect(Collectors.toList());

        return ArchitectureSummaryResponse.builder()
                .projectId(projectId)
                .totalApplications(apps.size())
                .totalControllers(controllers.size())
                .totalEndpoints(endpoints.size())
                .totalServices(services.size())
                .totalRepositories(repositories.size())
                .totalKafkaTopics(topics.size())
                .totalKafkaListeners(listeners.size())
                .totalConfigurations((int) configurationsCount)
                .totalDatabaseTables((int) databaseTablesCount)
                .endpointsByMethod(endpointsByMethod)
                .repositoriesByType(repositoriesByType)
                .applications(appSummaries)
                .build();
    }

    /**
     * Build full graph visualization for a project.
     */
    public GraphVisualizationResponse buildFullGraphVisualization(String projectId, int depth, List<String> nodeTypes) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> addedNodeIds = new HashSet<>();
        Set<String> addedEdgeIds = new HashSet<>();

        List<ApplicationNode> apps = applicationNodeRepository.findByProjectId(projectId);
        Map<String, ApplicationNode> appsByKey = apps.stream()
                .collect(Collectors.toMap(ApplicationNode::getAppKey, app -> app));

        List<ControllerNode> controllers = controllerNodeRepository.findByProjectIdWithEndpoints(projectId);
        for (ControllerNode controller : controllers) {
            ApplicationNode app = appsByKey.get(controller.getAppKey());
            if (app != null) {
                if (app.getControllers() == null) {
                    app.setControllers(new HashSet<>());
                }
                app.getControllers().add(controller);
            }
        }

        List<ServiceNode> services = serviceNodeRepository.findByProjectIdWithMethods(projectId);
        for (ServiceNode service : services) {
            ApplicationNode app = appsByKey.get(service.getAppKey());
            if (app != null) {
                if (app.getServices() == null) {
                    app.setServices(new HashSet<>());
                }
                app.getServices().add(service);
            }
        }

        List<RepositoryClassNode> repositories = repositoryClassNodeRepository.findByProjectIdWithDatabaseTables(projectId);
        for (RepositoryClassNode repository : repositories) {
            ApplicationNode app = appsByKey.get(repository.getAppKey());
            if (app != null) {
                if (app.getRepositories() == null) {
                    app.setRepositories(new HashSet<>());
                }
                app.getRepositories().add(repository);
            }
        }

        List<KafkaListenerNode> listeners = kafkaListenerNodeRepository.findByProjectIdWithMethods(projectId);
        for (KafkaListenerNode listener : listeners) {
            ApplicationNode app = appsByKey.get(listener.getAppKey());
            if (app != null) {
                if (app.getKafkaListeners() == null) {
                    app.setKafkaListeners(new HashSet<>());
                }
                app.getKafkaListeners().add(listener);
            }
        }

        for (ApplicationNode app : apps) {
            // Add application node
            addNodeIfNotExists(nodes, addedNodeIds, convertApplicationToGraphNode(app));

            // Process controllers
            if (app.getControllers() != null && shouldIncludeType(nodeTypes, "Controller")) {
                for (ControllerNode controller : app.getControllers()) {
                    GraphNode controllerNode = convertControllerToGraphNode(controller);
                    addNodeIfNotExists(nodes, addedNodeIds, controllerNode);
                    addEdgeIfNotExists(edges, addedEdgeIds, createEdge(app.getId(), controller.getId(), "CONTAINS_CONTROLLER"));

                    // Process endpoints
                    if (controller.getEndpoints() != null && shouldIncludeType(nodeTypes, "Endpoint")) {
                        for (EndpointNode endpoint : controller.getEndpoints()) {
                            GraphNode endpointNode = convertEndpointToGraphNode(endpoint);
                            addNodeIfNotExists(nodes, addedNodeIds, endpointNode);
                            addEdgeIfNotExists(edges, addedEdgeIds, createEdge(controller.getId(), endpoint.getId(), "HAS_ENDPOINT"));

                            // Process method calls from endpoint
                            if (depth > 1 && endpoint.getCalls() != null && shouldIncludeType(nodeTypes, "Method")) {
                                processMethodCalls(endpoint.getCalls(), endpoint.getId(), nodes, edges, addedNodeIds, addedEdgeIds, depth - 1);
                            }
                        }
                    }
                }
            }

            // Process services
            if (app.getServices() != null && shouldIncludeType(nodeTypes, "Service")) {
                for (ServiceNode service : app.getServices()) {
                    GraphNode serviceNode = convertServiceToGraphNode(service);
                    addNodeIfNotExists(nodes, addedNodeIds, serviceNode);
                    addEdgeIfNotExists(edges, addedEdgeIds, createEdge(app.getId(), service.getId(), "CONTAINS_SERVICE"));

                    // Process service methods
                    if (depth > 1 && service.getMethods() != null && shouldIncludeType(nodeTypes, "Method")) {
                        for (MethodNode method : service.getMethods()) {
                            GraphNode methodNode = convertMethodToGraphNode(method);
                            addNodeIfNotExists(nodes, addedNodeIds, methodNode);
                            addEdgeIfNotExists(edges, addedEdgeIds, createEdge(service.getId(), method.getId(), "HAS_METHOD"));
                        }
                    }
                }
            }

            // Process repositories
            if (app.getRepositories() != null && shouldIncludeType(nodeTypes, "Repository")) {
                for (RepositoryClassNode repo : app.getRepositories()) {
                    GraphNode repoNode = convertRepositoryToGraphNode(repo);
                    addNodeIfNotExists(nodes, addedNodeIds, repoNode);
                    addEdgeIfNotExists(edges, addedEdgeIds, createEdge(app.getId(), repo.getId(), "CONTAINS_REPOSITORY"));

                    // Add database table if exists
                    if (repo.getAccessesTables() != null && !repo.getAccessesTables().isEmpty() && shouldIncludeType(nodeTypes, "DatabaseTable")) {
                        DatabaseTableNode table = repo.getAccessesTables().iterator().next();
                        GraphNode tableNode = convertDatabaseTableToGraphNode(table);
                        addNodeIfNotExists(nodes, addedNodeIds, tableNode);
                        addEdgeIfNotExists(edges, addedEdgeIds, createEdge(repo.getId(), table.getId(), "ACCESSES"));
                    }
                }
            }

            // Process Kafka listeners
            if (app.getKafkaListeners() != null && shouldIncludeType(nodeTypes, "KafkaListener")) {
                for (KafkaListenerNode listener : app.getKafkaListeners()) {
                    GraphNode listenerNode = convertKafkaListenerToGraphNode(listener);
                    addNodeIfNotExists(nodes, addedNodeIds, listenerNode);
                    addEdgeIfNotExists(edges, addedEdgeIds, createEdge(app.getId(), listener.getId(), "CONTAINS_KAFKA_LISTENER"));
                }
            }
        }

        // Build metadata
        GraphMetadata metadata = buildMetadata(nodes, edges, projectId, depth);

        return GraphVisualizationResponse.builder()
                .nodes(nodes)
                .edges(edges)
                .metadata(metadata)
                .build();
    }

    /**
     * Build service dependency visualization.
     */
    public GraphVisualizationResponse buildServiceDependencyGraph(String projectId) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> addedNodeIds = new HashSet<>();

        List<ServiceNode> services = serviceNodeRepository.findByProjectIdWithMethods(projectId);

        // Add service nodes
        for (ServiceNode service : services) {
            GraphNode serviceNode = convertServiceToGraphNode(service);
            addNodeIfNotExists(nodes, addedNodeIds, serviceNode);
        }

        // Analyze method calls to find service dependencies
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (ServiceNode service : services) {
            if (service.getMethods() != null) {
                for (MethodNode method : service.getMethods()) {
                    if (method.getCalls() != null) {
                        for (MethodNode calledMethod : method.getCalls()) {
                            // Find which service owns the called method
                            String targetServiceId = findServiceForMethod(services, calledMethod);
                            if (targetServiceId != null && !targetServiceId.equals(service.getId())) {
                                dependencies.computeIfAbsent(service.getId(), k -> new HashSet<>()).add(targetServiceId);
                            }
                        }
                    }
                }
            }
        }

        // Create edges for dependencies
        int edgeId = 0;
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            for (String targetId : entry.getValue()) {
                edges.add(GraphEdge.builder()
                        .id("dep-" + (edgeId++))
                        .source(entry.getKey())
                        .target(targetId)
                        .type("DEPENDS_ON")
                        .label("depends on")
                        .style(EdgeStyle.builder()
                                .color("#2196F3")
                                .width(2)
                                .arrowShape("triangle")
                                .build())
                        .build());
            }
        }

        GraphMetadata metadata = buildMetadata(nodes, edges, projectId, 1);
        return GraphVisualizationResponse.builder()
                .nodes(nodes)
                .edges(edges)
                .metadata(metadata)
                .build();
    }

    /**
     * Build Kafka flow visualization.
     */
    public GraphVisualizationResponse buildKafkaFlowGraph(String projectId) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> addedNodeIds = new HashSet<>();
        Set<String> addedEdgeIds = new HashSet<>();

        // Get all Kafka topics
        List<KafkaTopicNode> topics = kafkaTopicNodeRepository.findByProjectId(projectId);

        // Add topic nodes
        for (KafkaTopicNode topic : topics) {
            GraphNode topicNode = GraphNode.builder()
                    .id(topic.getId())
                    .label(topic.getName())
                    .type("KafkaTopic")
                    .group("topics")
                    .properties(Map.of("name", topic.getName()))
                    .style(NodeStyle.builder()
                            .color(NODE_COLORS.get("KafkaTopic"))
                            .shape("diamond")
                            .size(40)
                            .icon("kafka")
                            .build())
                    .build();
            addNodeIfNotExists(nodes, addedNodeIds, topicNode);
        }

        // Find producers (endpoints and methods that produce to topics)
        List<EndpointNode> endpoints = endpointNodeRepository.findByProjectId(projectId);
        for (EndpointNode endpoint : endpoints) {
            if (endpoint.getProducesToTopics() != null && !endpoint.getProducesToTopics().isEmpty()) {
                GraphNode producerNode = GraphNode.builder()
                        .id(endpoint.getId())
                        .label(endpoint.getHttpMethod() + " " + endpoint.getPath())
                        .type("Producer")
                        .group("producers")
                        .properties(Map.of(
                                "httpMethod", endpoint.getHttpMethod(),
                                "path", endpoint.getPath(),
                                "controllerClass", endpoint.getControllerClass() != null ? endpoint.getControllerClass() : ""))
                        .style(NodeStyle.builder()
                                .color("#4CAF50")
                                .shape("rectangle")
                                .icon("endpoint")
                                .build())
                        .build();
                addNodeIfNotExists(nodes, addedNodeIds, producerNode);

                for (KafkaTopicNode topic : endpoint.getProducesToTopics()) {
                    addEdgeIfNotExists(edges, addedEdgeIds, createEdge(endpoint.getId(), topic.getId(), "PRODUCES_TO"));
                }
            }
        }

        // Find consumers (Kafka listeners)
        List<KafkaListenerNode> listeners = kafkaListenerNodeRepository.findByProjectIdWithListenerMethods(projectId);
        for (KafkaListenerNode listener : listeners) {
            if (listener.getListenerMethods() != null) {
                for (KafkaListenerMethodNode method : listener.getListenerMethods()) {
                    GraphNode consumerNode = GraphNode.builder()
                            .id(method.getId())
                            .label(listener.getClassName() + "." + method.getMethodName())
                            .type("Consumer")
                            .group("consumers")
                            .properties(Map.of(
                                    "className", listener.getClassName(),
                                    "methodName", method.getMethodName(),
                                    "groupId", method.getGroupId() != null ? method.getGroupId() : ""))
                            .style(NodeStyle.builder()
                                    .color("#F44336")
                                    .shape("rectangle")
                                    .icon("listener")
                                    .build())
                            .build();
                    addNodeIfNotExists(nodes, addedNodeIds, consumerNode);

                    // Link consumer to topic
                    if (method.getConsumesFromTopics() != null) {
                        for (KafkaTopicNode topic : method.getConsumesFromTopics()) {
                            addEdgeIfNotExists(edges, addedEdgeIds, createEdge(topic.getId(), method.getId(), "CONSUMED_BY"));
                        }
                    }
                }
            }
        }

        GraphMetadata metadata = buildMetadata(nodes, edges, projectId, 1);
        return GraphVisualizationResponse.builder()
                .nodes(nodes)
                .edges(edges)
                .metadata(metadata)
                .build();
    }

    /**
     * Build call chain visualization starting from an endpoint or method.
     */
    public CallChainResponse buildCallChainVisualization(String projectId, String startNodeId, int maxDepth, String direction) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        List<CallChainResponse.CallChainEntry> callChain = new ArrayList<>();
        Set<String> addedNodeIds = new HashSet<>();
        Set<String> addedEdgeIds = new HashSet<>();

        // Find the starting node (could be endpoint or method)
        GraphNode rootNode = null;
        Optional<EndpointNode> endpointOpt = endpointNodeRepository.findById(startNodeId);
        if (endpointOpt.isPresent()) {
            EndpointNode endpoint = endpointOpt.get();
            rootNode = convertEndpointToGraphNode(endpoint);
            addNodeIfNotExists(nodes, addedNodeIds, rootNode);

            if ("callees".equals(direction) && endpoint.getCalls() != null) {
                processCallChain(endpoint.getCalls(), endpoint.getId(), rootNode.getLabel(), rootNode.getType(),
                        nodes, edges, callChain, addedNodeIds, addedEdgeIds, 1, maxDepth);
            }
        }

        if (rootNode == null) {
            Optional<MethodNode> methodOpt = methodNodeRepository.findById(startNodeId);
            if (methodOpt.isPresent()) {
                MethodNode method = methodOpt.get();
                rootNode = convertMethodToGraphNode(method);
                addNodeIfNotExists(nodes, addedNodeIds, rootNode);

                if ("callees".equals(direction) && method.getCalls() != null) {
                    processCallChain(method.getCalls(), method.getId(), rootNode.getLabel(), rootNode.getType(),
                            nodes, edges, callChain, addedNodeIds, addedEdgeIds, 1, maxDepth);
                }
            }
        }

        GraphMetadata metadata = buildMetadata(nodes, edges, projectId, maxDepth);
        GraphVisualizationResponse visualizationFormat = GraphVisualizationResponse.builder()
                .nodes(nodes)
                .edges(edges)
                .metadata(metadata)
                .build();

        return CallChainResponse.builder()
                .root(rootNode)
                .callChain(callChain)
                .visualizationFormat(visualizationFormat)
                .maxDepth(maxDepth)
                .direction(direction)
                .build();
    }

    /**
     * Build endpoints graph with optional filtering.
     */
    public GraphVisualizationResponse buildEndpointsGraph(String projectId, String httpMethod, String pathPattern) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> addedNodeIds = new HashSet<>();
        Set<String> addedEdgeIds = new HashSet<>();

        List<EndpointNode> endpoints;
        if (httpMethod != null && !httpMethod.isEmpty()) {
            endpoints = endpointNodeRepository.findByProjectIdAndHttpMethod(projectId, httpMethod);
        } else if (pathPattern != null && !pathPattern.isEmpty()) {
            String regex = pathPattern.replace("*", ".*");
            endpoints = endpointNodeRepository.findByPathPattern(projectId, regex);
        } else {
            endpoints = endpointNodeRepository.findByProjectId(projectId);
        }

        for (EndpointNode endpoint : endpoints) {
            GraphNode endpointNode = convertEndpointToGraphNode(endpoint);
            addNodeIfNotExists(nodes, addedNodeIds, endpointNode);

            // Add called methods
            if (endpoint.getCalls() != null) {
                for (MethodNode method : endpoint.getCalls()) {
                    GraphNode methodNode = convertMethodToGraphNode(method);
                    addNodeIfNotExists(nodes, addedNodeIds, methodNode);
                    addEdgeIfNotExists(edges, addedEdgeIds, createEdge(endpoint.getId(), method.getId(), "CALLS"));
                }
            }
        }

        GraphMetadata metadata = buildMetadata(nodes, edges, projectId, 2);
        return GraphVisualizationResponse.builder()
                .nodes(nodes)
                .edges(edges)
                .metadata(metadata)
                .build();
    }

    /**
     * Build external calls graph.
     */
    public GraphVisualizationResponse buildExternalCallsGraph(String projectId, Boolean resolvedOnly) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> addedNodeIds = new HashSet<>();
        Set<String> addedEdgeIds = new HashSet<>();

        List<EndpointNode> endpoints = endpointNodeRepository.findByProjectIdWithExternalCalls(projectId);

        for (EndpointNode endpoint : endpoints) {
            if (endpoint.getExternalCalls() != null && !endpoint.getExternalCalls().isEmpty()) {
                GraphNode endpointNode = convertEndpointToGraphNode(endpoint);
                addNodeIfNotExists(nodes, addedNodeIds, endpointNode);

                for (ExternalCallNode extCall : endpoint.getExternalCalls()) {
                    if (resolvedOnly != null && resolvedOnly && !extCall.isResolved()) {
                        continue;
                    }
                    if (resolvedOnly != null && !resolvedOnly && extCall.isResolved()) {
                        continue;
                    }

                    GraphNode extCallNode = GraphNode.builder()
                            .id(extCall.getId())
                            .label(extCall.getHttpMethod() + " " + extCall.getUrl())
                            .type("ExternalCall")
                            .group("external")
                            .properties(Map.of(
                                    "httpMethod", extCall.getHttpMethod() != null ? extCall.getHttpMethod() : "UNKNOWN",
                                    "url", extCall.getUrl() != null ? extCall.getUrl() : "",
                                    "clientType", extCall.getClientType() != null ? extCall.getClientType() : "",
                                    "resolved", extCall.isResolved()))
                            .style(NodeStyle.builder()
                                    .color(extCall.isResolved() ? "#4CAF50" : "#F44336")
                                    .shape("hexagon")
                                    .build())
                            .build();
                    addNodeIfNotExists(nodes, addedNodeIds, extCallNode);
                    addEdgeIfNotExists(edges, addedEdgeIds, createEdge(endpoint.getId(), extCall.getId(), "MAKES_EXTERNAL_CALL"));
                }
            }
        }

        GraphMetadata metadata = buildMetadata(nodes, edges, projectId, 1);
        return GraphVisualizationResponse.builder()
                .nodes(nodes)
                .edges(edges)
                .metadata(metadata)
                .build();
    }

    /**
     * Build database access graph.
     */
    public GraphVisualizationResponse buildDatabaseAccessGraph(String projectId) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> addedNodeIds = new HashSet<>();
        Set<String> addedEdgeIds = new HashSet<>();

        List<RepositoryClassNode> repositories = repositoryClassNodeRepository.findByProjectIdWithDatabaseTables(projectId);

        for (RepositoryClassNode repo : repositories) {
            GraphNode repoNode = convertRepositoryToGraphNode(repo);
            addNodeIfNotExists(nodes, addedNodeIds, repoNode);

            if (repo.getAccessesTables() != null && !repo.getAccessesTables().isEmpty()) {
                DatabaseTableNode table = repo.getAccessesTables().iterator().next();
                GraphNode tableNode = convertDatabaseTableToGraphNode(table);
                addNodeIfNotExists(nodes, addedNodeIds, tableNode);
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(repo.getId(), table.getId(), "ACCESSES"));
            }
        }

        GraphMetadata metadata = buildMetadata(nodes, edges, projectId, 1);
        return GraphVisualizationResponse.builder()
                .nodes(nodes)
                .edges(edges)
                .metadata(metadata)
                .build();
    }

    // ==================== Node Query APIs ====================

    /**
     * Get all nodes of a specific type in a project.
     * Supported types: Application, Controller, Endpoint, Service, Method, Repository,
     * DatabaseTable, KafkaTopic, KafkaListener, Configuration, ExternalCall
     */
    public NodeListResponse getNodesByType(String projectId, String nodeType) {
        log.info("Getting nodes by type for project: {}, type: {}", projectId, nodeType);

        List<GraphNode> nodes = new ArrayList<>();

        switch (nodeType) {
            case "Application":
                List<ApplicationNode> apps = applicationNodeRepository.findByProjectId(projectId);
                nodes = apps.stream().map(this::convertApplicationToGraphNode).collect(Collectors.toList());
                break;
            case "Controller":
                List<ControllerNode> controllers = controllerNodeRepository.findByProjectId(projectId);
                nodes = controllers.stream().map(this::convertControllerToGraphNode).collect(Collectors.toList());
                break;
            case "Endpoint":
                List<EndpointNode> endpoints = endpointNodeRepository.findByProjectId(projectId);
                nodes = endpoints.stream().map(this::convertEndpointToGraphNode).collect(Collectors.toList());
                break;
            case "Service":
                List<ServiceNode> services = serviceNodeRepository.findByProjectId(projectId);
                nodes = services.stream().map(this::convertServiceToGraphNode).collect(Collectors.toList());
                break;
            case "Method":
                List<MethodNode> methods = methodNodeRepository.findByProjectId(projectId);
                nodes = methods.stream().map(this::convertMethodToGraphNode).collect(Collectors.toList());
                break;
            case "Repository":
                List<RepositoryClassNode> repositories = repositoryClassNodeRepository.findByProjectId(projectId);
                nodes = repositories.stream().map(this::convertRepositoryToGraphNode).collect(Collectors.toList());
                break;
            case "KafkaTopic":
                List<KafkaTopicNode> topics = kafkaTopicNodeRepository.findByProjectId(projectId);
                nodes = topics.stream().map(this::convertKafkaTopicToGraphNode).collect(Collectors.toList());
                break;
            case "KafkaListener":
                List<KafkaListenerNode> listeners = kafkaListenerNodeRepository.findByProjectId(projectId);
                nodes = listeners.stream().map(this::convertKafkaListenerToGraphNode).collect(Collectors.toList());
                break;
            case "DatabaseTable":
                List<RepositoryClassNode> reposWithTables = repositoryClassNodeRepository.findByProjectIdWithDatabaseTables(projectId);
                for (RepositoryClassNode repo : reposWithTables) {
                    if (repo.getAccessesTables() != null) {
                        for (DatabaseTableNode table : repo.getAccessesTables()) {
                            nodes.add(convertDatabaseTableToGraphNode(table));
                        }
                    }
                }
                break;
            default:
                log.warn("Unknown node type: {}", nodeType);
        }

        return NodeListResponse.builder()
                .nodes(nodes)
                .nodeType(nodeType)
                .totalCount(nodes.size())
                .projectId(projectId)
                .build();
    }

    /**
     * Get a node by its ID with all related nodes and edges for visualization.
     * Returns the node with complete depth traversal - all downstream dependencies are included.
     */
    public GraphVisualizationResponse getNodeGraphById(String projectId, String nodeId) {
        log.info("Getting node graph by ID for project: {}, nodeId: {}", projectId, nodeId);

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> addedNodeIds = new HashSet<>();
        Set<String> addedEdgeIds = new HashSet<>();

        // Try to find the node in each repository and build the full graph
        // First, try Application
        Optional<ApplicationNode> appOpt = applicationNodeRepository.findById(nodeId);
        if (appOpt.isPresent()) {
            ApplicationNode app = appOpt.get();
            if (!projectId.equals(app.getProjectId())) {
                return buildEmptyResponse(projectId);
            }
            buildApplicationNodeGraphFull(app, projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            return buildFinalResponse(nodes, edges, projectId);
        }

        // Try Controller
        Optional<ControllerNode> ctrlOpt = controllerNodeRepository.findByIdWithFullDetails(nodeId);
        if (ctrlOpt.isPresent()) {
            ControllerNode controller = ctrlOpt.get();
            if (!projectId.equals(controller.getProjectId())) {
                return buildEmptyResponse(projectId);
            }
            buildControllerNodeGraphFull(controller, projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            return buildFinalResponse(nodes, edges, projectId);
        }

        // Try Endpoint
        Optional<EndpointNode> endptOpt = endpointNodeRepository.findByIdWithFullCallChain(nodeId);
        if (endptOpt.isPresent()) {
            EndpointNode endpoint = endptOpt.get();
            if (!projectId.equals(endpoint.getProjectId())) {
                return buildEmptyResponse(projectId);
            }
            buildEndpointNodeGraphFull(endpoint, projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            return buildFinalResponse(nodes, edges, projectId);
        }

        // Try Service
        Optional<ServiceNode> svcOpt = serviceNodeRepository.findByIdWithFullDetails(nodeId);
        if (svcOpt.isPresent()) {
            ServiceNode service = svcOpt.get();
            if (!projectId.equals(service.getProjectId())) {
                return buildEmptyResponse(projectId);
            }
            buildServiceNodeGraphFull(service, projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            return buildFinalResponse(nodes, edges, projectId);
        }

        // Try Method
        Optional<MethodNode> methodOpt = methodNodeRepository.findById(nodeId);
        if (methodOpt.isPresent()) {
            MethodNode method = methodOpt.get();
            if (!projectId.equals(method.getProjectId())) {
                return buildEmptyResponse(projectId);
            }
            buildMethodNodeGraphFull(method, projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            return buildFinalResponse(nodes, edges, projectId);
        }

        // Try KafkaTopic
        Optional<KafkaTopicNode> topicOpt = kafkaTopicNodeRepository.findById(nodeId);
        if (topicOpt.isPresent()) {
            KafkaTopicNode topic = topicOpt.get();
            if (!projectId.equals(topic.getProjectId())) {
                return buildEmptyResponse(projectId);
            }
            buildKafkaTopicNodeGraphFull(topic, projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            return buildFinalResponse(nodes, edges, projectId);
        }

        // Try KafkaListener
        Optional<KafkaListenerNode> listenerOpt = kafkaListenerNodeRepository.findById(nodeId);
        if (listenerOpt.isPresent()) {
            KafkaListenerNode listener = listenerOpt.get();
            if (!projectId.equals(listener.getProjectId())) {
                return buildEmptyResponse(projectId);
            }
            buildKafkaListenerNodeGraphFull(listener, projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            return buildFinalResponse(nodes, edges, projectId);
        }

        // Try Repository
        Optional<RepositoryClassNode> repoOpt = repositoryClassNodeRepository.findById(nodeId);
        if (repoOpt.isPresent()) {
            RepositoryClassNode repo = repoOpt.get();
            if (!projectId.equals(repo.getProjectId())) {
                return buildEmptyResponse(projectId);
            }
            buildRepositoryNodeGraphFull(repo, projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            return buildFinalResponse(nodes, edges, projectId);
        }

        // Node not found
        log.warn("Node not found with ID: {}", nodeId);
        return buildEmptyResponse(projectId);
    }

    private GraphVisualizationResponse buildFinalResponse(List<GraphNode> nodes, List<GraphEdge> edges, String projectId) {
        // Calculate actual depth from the graph
        int depth = calculateGraphDepth(nodes, edges);
        GraphMetadata metadata = buildMetadata(nodes, edges, projectId, depth);
        return GraphVisualizationResponse.builder()
                .nodes(nodes)
                .edges(edges)
                .metadata(metadata)
                .build();
    }

    private int calculateGraphDepth(List<GraphNode> nodes, List<GraphEdge> edges) {
        if (nodes.isEmpty()) return 0;
        // Simple heuristic: count unique node types as a proxy for depth
        Set<String> types = new HashSet<>();
        for (GraphNode node : nodes) {
            types.add(node.getType());
        }
        return types.size();
    }

    private GraphVisualizationResponse buildEmptyResponse(String projectId) {
        return GraphVisualizationResponse.builder()
                .nodes(Collections.emptyList())
                .edges(Collections.emptyList())
                .metadata(buildMetadata(Collections.emptyList(), Collections.emptyList(), projectId, 1))
                .build();
    }

    // ==================== Full Depth Graph Building Methods ====================

    /**
     * Build complete graph for an Application node with full depth traversal.
     * Includes: Controllers -> Endpoints -> Methods -> called Methods/External calls/Kafka
     *           Services -> Methods -> called Methods/External calls/Kafka
     *           Repositories -> Database Tables
     *           KafkaListeners -> ListenerMethods -> Topics
     */
    private void buildApplicationNodeGraphFull(ApplicationNode app, String projectId,
                                               List<GraphNode> nodes, List<GraphEdge> edges,
                                               Set<String> addedNodeIds, Set<String> addedEdgeIds) {
        addNodeIfNotExists(nodes, addedNodeIds, convertApplicationToGraphNode(app));

        // Get and process controllers with full depth
        List<ControllerNode> controllers = controllerNodeRepository.findByProjectIdWithEndpoints(projectId);
        for (ControllerNode ctrl : controllers) {
            if (app.getAppKey().equals(ctrl.getAppKey())) {
                addNodeIfNotExists(nodes, addedNodeIds, convertControllerToGraphNode(ctrl));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(app.getId(), ctrl.getId(), "CONTAINS_CONTROLLER"));

                // Process endpoints under this controller
                if (ctrl.getEndpoints() != null) {
                    for (EndpointNode endpoint : ctrl.getEndpoints()) {
                        processEndpointFull(endpoint, ctrl.getId(), projectId, nodes, edges, addedNodeIds, addedEdgeIds);
                    }
                }
            }
        }

        // Get and process services with full depth
        List<ServiceNode> services = serviceNodeRepository.findByProjectIdWithMethods(projectId);
        for (ServiceNode svc : services) {
            if (app.getAppKey().equals(svc.getAppKey())) {
                addNodeIfNotExists(nodes, addedNodeIds, convertServiceToGraphNode(svc));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(app.getId(), svc.getId(), "CONTAINS_SERVICE"));

                // Process methods under this service
                if (svc.getMethods() != null) {
                    for (MethodNode method : svc.getMethods()) {
                        processMethodFull(method, svc.getId(), "HAS_METHOD", projectId, nodes, edges, addedNodeIds, addedEdgeIds);
                    }
                }
            }
        }

        // Get and process repositories with database tables
        List<RepositoryClassNode> repos = repositoryClassNodeRepository.findByProjectIdWithDatabaseTables(projectId);
        for (RepositoryClassNode repo : repos) {
            if (app.getAppKey().equals(repo.getAppKey())) {
                addNodeIfNotExists(nodes, addedNodeIds, convertRepositoryToGraphNode(repo));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(app.getId(), repo.getId(), "CONTAINS_REPOSITORY"));

                if (repo.getAccessesTables() != null) {
                    for (DatabaseTableNode table : repo.getAccessesTables()) {
                        addNodeIfNotExists(nodes, addedNodeIds, convertDatabaseTableToGraphNode(table));
                        addEdgeIfNotExists(edges, addedEdgeIds, createEdge(repo.getId(), table.getId(), "ACCESSES"));
                    }
                }
            }
        }

        // Get and process kafka listeners with full depth
        List<KafkaListenerNode> listeners = kafkaListenerNodeRepository.findByProjectIdWithListenerMethods(projectId);
        for (KafkaListenerNode listener : listeners) {
            if (app.getAppKey().equals(listener.getAppKey())) {
                processKafkaListenerFull(listener, app.getId(), projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            }
        }
    }

    /**
     * Build complete graph for a Controller node with full depth traversal.
     */
    private void buildControllerNodeGraphFull(ControllerNode controller, String projectId,
                                              List<GraphNode> nodes, List<GraphEdge> edges,
                                              Set<String> addedNodeIds, Set<String> addedEdgeIds) {
        addNodeIfNotExists(nodes, addedNodeIds, convertControllerToGraphNode(controller));

        // Add parent application
        Optional<ApplicationNode> appOpt = applicationNodeRepository.findByProjectIdAndAppKey(projectId, controller.getAppKey());
        appOpt.ifPresent(app -> {
            addNodeIfNotExists(nodes, addedNodeIds, convertApplicationToGraphNode(app));
            addEdgeIfNotExists(edges, addedEdgeIds, createEdge(app.getId(), controller.getId(), "CONTAINS_CONTROLLER"));
        });

        // Process endpoints with full depth
        if (controller.getEndpoints() != null) {
            for (EndpointNode endpoint : controller.getEndpoints()) {
                processEndpointFull(endpoint, controller.getId(), projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            }
        }
    }

    /**
     * Build complete graph for an Endpoint node with full depth traversal.
     */
    private void buildEndpointNodeGraphFull(EndpointNode endpoint, String projectId,
                                            List<GraphNode> nodes, List<GraphEdge> edges,
                                            Set<String> addedNodeIds, Set<String> addedEdgeIds) {
        addNodeIfNotExists(nodes, addedNodeIds, convertEndpointToGraphNode(endpoint));

        // Add parent controller
        if (endpoint.getControllerClass() != null) {
            Optional<ControllerNode> ctrlOpt = controllerNodeRepository.findByProjectIdAndClassName(projectId, endpoint.getControllerClass());
            ctrlOpt.ifPresent(ctrl -> {
                addNodeIfNotExists(nodes, addedNodeIds, convertControllerToGraphNode(ctrl));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(ctrl.getId(), endpoint.getId(), "HAS_ENDPOINT"));
            });
        }

        // Process called methods recursively
        if (endpoint.getCalls() != null) {
            for (MethodNode method : endpoint.getCalls()) {
                processMethodFull(method, endpoint.getId(), "CALLS", projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            }
        }

        // Add external calls
        if (endpoint.getExternalCalls() != null) {
            for (ExternalCallNode extCall : endpoint.getExternalCalls()) {
                addNodeIfNotExists(nodes, addedNodeIds, convertExternalCallToGraphNode(extCall));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(endpoint.getId(), extCall.getId(), "MAKES_EXTERNAL_CALL"));
            }
        }

        // Add Kafka topics
        if (endpoint.getProducesToTopics() != null) {
            for (KafkaTopicNode topic : endpoint.getProducesToTopics()) {
                addNodeIfNotExists(nodes, addedNodeIds, convertKafkaTopicToGraphNode(topic));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(endpoint.getId(), topic.getId(), "PRODUCES_TO"));
            }
        }
    }

    /**
     * Build complete graph for a Service node with full depth traversal.
     */
    private void buildServiceNodeGraphFull(ServiceNode service, String projectId,
                                           List<GraphNode> nodes, List<GraphEdge> edges,
                                           Set<String> addedNodeIds, Set<String> addedEdgeIds) {
        addNodeIfNotExists(nodes, addedNodeIds, convertServiceToGraphNode(service));

        // Add parent application
        Optional<ApplicationNode> appOpt = applicationNodeRepository.findByProjectIdAndAppKey(projectId, service.getAppKey());
        appOpt.ifPresent(app -> {
            addNodeIfNotExists(nodes, addedNodeIds, convertApplicationToGraphNode(app));
            addEdgeIfNotExists(edges, addedEdgeIds, createEdge(app.getId(), service.getId(), "CONTAINS_SERVICE"));
        });

        // Process methods with full depth
        if (service.getMethods() != null) {
            for (MethodNode method : service.getMethods()) {
                processMethodFull(method, service.getId(), "HAS_METHOD", projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            }
        }
    }

    /**
     * Build complete graph for a Method node with full depth traversal.
     */
    private void buildMethodNodeGraphFull(MethodNode method, String projectId,
                                          List<GraphNode> nodes, List<GraphEdge> edges,
                                          Set<String> addedNodeIds, Set<String> addedEdgeIds) {
        addNodeIfNotExists(nodes, addedNodeIds, convertMethodToGraphNode(method));

        // Add parent service if method belongs to one
        if (method.getClassName() != null) {
            Optional<ServiceNode> svcOpt = serviceNodeRepository.findByProjectIdAndClassName(projectId, method.getClassName());
            svcOpt.ifPresent(svc -> {
                addNodeIfNotExists(nodes, addedNodeIds, convertServiceToGraphNode(svc));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(svc.getId(), method.getId(), "HAS_METHOD"));
            });
        }

        // Process called methods recursively
        if (method.getCalls() != null) {
            for (MethodNode calledMethod : method.getCalls()) {
                processMethodFull(calledMethod, method.getId(), "CALLS", projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            }
        }

        // Add callers (methods that call this method)
        if (method.getSignature() != null) {
            List<MethodNode> callers = methodNodeRepository.findCallersOf(projectId, method.getSignature());
            for (MethodNode caller : callers) {
                addNodeIfNotExists(nodes, addedNodeIds, convertMethodToGraphNode(caller));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(caller.getId(), method.getId(), "CALLS"));
            }
        }
    }

    /**
     * Build complete graph for a KafkaTopic node with full depth traversal.
     */
    private void buildKafkaTopicNodeGraphFull(KafkaTopicNode topic, String projectId,
                                              List<GraphNode> nodes, List<GraphEdge> edges,
                                              Set<String> addedNodeIds, Set<String> addedEdgeIds) {
        addNodeIfNotExists(nodes, addedNodeIds, convertKafkaTopicToGraphNode(topic));

        // Find producers - endpoints that produce to this topic
        List<EndpointNode> endpoints = endpointNodeRepository.findByProjectId(projectId);
        for (EndpointNode endpoint : endpoints) {
            if (endpoint.getProducesToTopics() != null) {
                for (KafkaTopicNode t : endpoint.getProducesToTopics()) {
                    if (t.getId().equals(topic.getId())) {
                        addNodeIfNotExists(nodes, addedNodeIds, convertEndpointToGraphNode(endpoint));
                        addEdgeIfNotExists(edges, addedEdgeIds, createEdge(endpoint.getId(), topic.getId(), "PRODUCES_TO"));
                        break;
                    }
                }
            }
        }

        // Find consumers - kafka listeners that consume from this topic
        List<KafkaListenerNode> listeners = kafkaListenerNodeRepository.findByProjectIdWithListenerMethods(projectId);
        for (KafkaListenerNode listener : listeners) {
            if (listener.getListenerMethods() != null) {
                for (KafkaListenerMethodNode method : listener.getListenerMethods()) {
                    if (method.getConsumesFromTopics() != null) {
                        for (KafkaTopicNode t : method.getConsumesFromTopics()) {
                            if (t.getId().equals(topic.getId())) {
                                addNodeIfNotExists(nodes, addedNodeIds, convertKafkaListenerToGraphNode(listener));
                                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(listener.getId(), method.getId(), "HAS_LISTENER_METHOD"));

                                GraphNode consumerNode = convertKafkaListenerMethodToGraphNode(method, listener);
                                addNodeIfNotExists(nodes, addedNodeIds, consumerNode);
                                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(topic.getId(), method.getId(), "CONSUMED_BY"));
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Build complete graph for a KafkaListener node with full depth traversal.
     */
    private void buildKafkaListenerNodeGraphFull(KafkaListenerNode listener, String projectId,
                                                 List<GraphNode> nodes, List<GraphEdge> edges,
                                                 Set<String> addedNodeIds, Set<String> addedEdgeIds) {
        addNodeIfNotExists(nodes, addedNodeIds, convertKafkaListenerToGraphNode(listener));

        // Add parent application
        Optional<ApplicationNode> appOpt = applicationNodeRepository.findByProjectIdAndAppKey(projectId, listener.getAppKey());
        appOpt.ifPresent(app -> {
            addNodeIfNotExists(nodes, addedNodeIds, convertApplicationToGraphNode(app));
            addEdgeIfNotExists(edges, addedEdgeIds, createEdge(app.getId(), listener.getId(), "CONTAINS_KAFKA_LISTENER"));
        });

        // Add listener methods and their topics
        if (listener.getListenerMethods() != null) {
            for (KafkaListenerMethodNode method : listener.getListenerMethods()) {
                GraphNode methodNode = convertKafkaListenerMethodToGraphNode(method, listener);
                addNodeIfNotExists(nodes, addedNodeIds, methodNode);
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(listener.getId(), method.getId(), "HAS_LISTENER_METHOD"));

                // Add consumed topics
                if (method.getConsumesFromTopics() != null) {
                    for (KafkaTopicNode topic : method.getConsumesFromTopics()) {
                        addNodeIfNotExists(nodes, addedNodeIds, convertKafkaTopicToGraphNode(topic));
                        addEdgeIfNotExists(edges, addedEdgeIds, createEdge(topic.getId(), method.getId(), "CONSUMED_BY"));
                    }
                }
            }
        }
    }

    /**
     * Build complete graph for a Repository node with full depth traversal.
     */
    private void buildRepositoryNodeGraphFull(RepositoryClassNode repo, String projectId,
                                              List<GraphNode> nodes, List<GraphEdge> edges,
                                              Set<String> addedNodeIds, Set<String> addedEdgeIds) {
        addNodeIfNotExists(nodes, addedNodeIds, convertRepositoryToGraphNode(repo));

        // Add parent application
        Optional<ApplicationNode> appOpt = applicationNodeRepository.findByProjectIdAndAppKey(projectId, repo.getAppKey());
        appOpt.ifPresent(app -> {
            addNodeIfNotExists(nodes, addedNodeIds, convertApplicationToGraphNode(app));
            addEdgeIfNotExists(edges, addedEdgeIds, createEdge(app.getId(), repo.getId(), "CONTAINS_REPOSITORY"));
        });

        // Add database tables
        if (repo.getAccessesTables() != null) {
            for (DatabaseTableNode table : repo.getAccessesTables()) {
                addNodeIfNotExists(nodes, addedNodeIds, convertDatabaseTableToGraphNode(table));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(repo.getId(), table.getId(), "ACCESSES"));
            }
        }
    }

    // ==================== Recursive Processing Helpers ====================

    /**
     * Process an endpoint and all its downstream dependencies recursively.
     */
    private void processEndpointFull(EndpointNode endpoint, String parentId, String projectId,
                                     List<GraphNode> nodes, List<GraphEdge> edges,
                                     Set<String> addedNodeIds, Set<String> addedEdgeIds) {
        addNodeIfNotExists(nodes, addedNodeIds, convertEndpointToGraphNode(endpoint));
        addEdgeIfNotExists(edges, addedEdgeIds, createEdge(parentId, endpoint.getId(), "HAS_ENDPOINT"));

        // Process called methods recursively
        if (endpoint.getCalls() != null) {
            for (MethodNode method : endpoint.getCalls()) {
                processMethodFull(method, endpoint.getId(), "CALLS", projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            }
        }

        // Add external calls
        if (endpoint.getExternalCalls() != null) {
            for (ExternalCallNode extCall : endpoint.getExternalCalls()) {
                addNodeIfNotExists(nodes, addedNodeIds, convertExternalCallToGraphNode(extCall));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(endpoint.getId(), extCall.getId(), "MAKES_EXTERNAL_CALL"));
            }
        }

        // Add Kafka topics
        if (endpoint.getProducesToTopics() != null) {
            for (KafkaTopicNode topic : endpoint.getProducesToTopics()) {
                addNodeIfNotExists(nodes, addedNodeIds, convertKafkaTopicToGraphNode(topic));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(endpoint.getId(), topic.getId(), "PRODUCES_TO"));
            }
        }
    }

    /**
     * Process a method and all its downstream dependencies recursively.
     * Uses addedNodeIds to prevent infinite loops from circular dependencies.
     * Also handles repository methods and their database table access.
     */
    private void processMethodFull(MethodNode method, String parentId, String edgeType, String projectId,
                                   List<GraphNode> nodes, List<GraphEdge> edges,
                                   Set<String> addedNodeIds, Set<String> addedEdgeIds) {
        // Check if already processed to avoid infinite loops
        boolean isNewNode = !addedNodeIds.contains(method.getId());

        addNodeIfNotExists(nodes, addedNodeIds, convertMethodToGraphNode(method));
        addEdgeIfNotExists(edges, addedEdgeIds, createEdge(parentId, method.getId(), edgeType));

        // Only process children if this is a new node (not already processed)
        if (isNewNode && method.getCalls() != null) {
            for (MethodNode calledMethod : method.getCalls()) {
                processMethodFull(calledMethod, method.getId(), "CALLS", projectId, nodes, edges, addedNodeIds, addedEdgeIds);
            }
        }

        // Add external calls from this method
        if (isNewNode && method.getExternalCalls() != null) {
            for (ExternalCallNode extCall : method.getExternalCalls()) {
                addNodeIfNotExists(nodes, addedNodeIds, convertExternalCallToGraphNode(extCall));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(method.getId(), extCall.getId(), "MAKES_EXTERNAL_CALL"));
            }
        }

        // Add Kafka topics produced by this method
        if (isNewNode && method.getProducesToTopics() != null) {
            for (KafkaTopicNode topic : method.getProducesToTopics()) {
                addNodeIfNotExists(nodes, addedNodeIds, convertKafkaTopicToGraphNode(topic));
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(method.getId(), topic.getId(), "PRODUCES_TO"));
            }
        }

        // Check if this method belongs to a Repository and add database tables
        if (isNewNode && method.getClassName() != null) {
            // Check if method type indicates it's a repository method
            String methodType = method.getMethodType();
            if ("REPOSITORY_METHOD".equals(methodType) || method.getClassName().endsWith("Repository")) {
                // Use the query that loads database tables along with the repository
                Optional<RepositoryClassNode> repoOpt = repositoryClassNodeRepository
                        .findByProjectIdAndClassNameWithTables(projectId, method.getClassName());
                repoOpt.ifPresent(repo -> {
                    // Add repository node if not already added
                    addNodeIfNotExists(nodes, addedNodeIds, convertRepositoryToGraphNode(repo));
                    addEdgeIfNotExists(edges, addedEdgeIds, createEdge(repo.getId(), method.getId(), "HAS_METHOD"));

                    // Add database tables accessed by this repository
                    if (repo.getAccessesTables() != null) {
                        for (DatabaseTableNode table : repo.getAccessesTables()) {
                            addNodeIfNotExists(nodes, addedNodeIds, convertDatabaseTableToGraphNode(table));
                            addEdgeIfNotExists(edges, addedEdgeIds, createEdge(repo.getId(), table.getId(), "ACCESSES"));
                        }
                    }
                });
            }
        }
    }

    /**
     * Process a Kafka listener and all its downstream dependencies.
     */
    private void processKafkaListenerFull(KafkaListenerNode listener, String parentId, String projectId,
                                          List<GraphNode> nodes, List<GraphEdge> edges,
                                          Set<String> addedNodeIds, Set<String> addedEdgeIds) {
        addNodeIfNotExists(nodes, addedNodeIds, convertKafkaListenerToGraphNode(listener));
        addEdgeIfNotExists(edges, addedEdgeIds, createEdge(parentId, listener.getId(), "CONTAINS_KAFKA_LISTENER"));

        if (listener.getListenerMethods() != null) {
            for (KafkaListenerMethodNode method : listener.getListenerMethods()) {
                GraphNode methodNode = convertKafkaListenerMethodToGraphNode(method, listener);
                addNodeIfNotExists(nodes, addedNodeIds, methodNode);
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(listener.getId(), method.getId(), "HAS_LISTENER_METHOD"));

                // Add consumed topics
                if (method.getConsumesFromTopics() != null) {
                    for (KafkaTopicNode topic : method.getConsumesFromTopics()) {
                        addNodeIfNotExists(nodes, addedNodeIds, convertKafkaTopicToGraphNode(topic));
                        addEdgeIfNotExists(edges, addedEdgeIds, createEdge(topic.getId(), method.getId(), "CONSUMED_BY"));
                    }
                }
            }
        }
    }

    private GraphNode convertKafkaListenerMethodToGraphNode(KafkaListenerMethodNode method, KafkaListenerNode listener) {
        return GraphNode.builder()
                .id(method.getId())
                .label(listener.getClassName() + "." + method.getMethodName())
                .type("KafkaListenerMethod")
                .group(listener.getPackageName())
                .properties(Map.of(
                        "className", listener.getClassName(),
                        "methodName", method.getMethodName(),
                        "signature", method.getSignature() != null ? method.getSignature() : "",
                        "groupId", method.getGroupId() != null ? method.getGroupId() : ""))
                .style(NodeStyle.builder()
                        .color("#F44336")
                        .shape("rectangle")
                        .icon("listener-method")
                        .build())
                .build();
    }

    private GraphNode convertKafkaTopicToGraphNode(KafkaTopicNode topic) {
        Map<String, Object> props = new HashMap<>();
        props.put("name", topic.getName());

        return GraphNode.builder()
                .id(topic.getId())
                .label(topic.getName())
                .type("KafkaTopic")
                .group("topics")
                .properties(props)
                .style(NodeStyle.builder()
                        .color(NODE_COLORS.get("KafkaTopic"))
                        .shape("diamond")
                        .size(40)
                        .icon("kafka")
                        .build())
                .build();
    }

    private GraphNode convertExternalCallToGraphNode(ExternalCallNode extCall) {
        Map<String, Object> props = new HashMap<>();
        props.put("httpMethod", extCall.getHttpMethod() != null ? extCall.getHttpMethod() : "UNKNOWN");
        props.put("url", extCall.getUrl() != null ? extCall.getUrl() : "");
        props.put("clientType", extCall.getClientType() != null ? extCall.getClientType() : "");
        props.put("resolved", extCall.isResolved());

        return GraphNode.builder()
                .id(extCall.getId())
                .label((extCall.getHttpMethod() != null ? extCall.getHttpMethod() : "?") + " " +
                       (extCall.getUrl() != null ? extCall.getUrl() : ""))
                .type("ExternalCall")
                .group("external")
                .properties(props)
                .style(NodeStyle.builder()
                        .color(extCall.isResolved() ? "#4CAF50" : "#F44336")
                        .shape("hexagon")
                        .size(25)
                        .icon("external-call")
                        .build())
                .build();
    }

    // ==================== Helper Methods ====================

    private void processMethodCalls(Set<MethodNode> calls, String parentId,
                                    List<GraphNode> nodes, List<GraphEdge> edges,
                                    Set<String> addedNodeIds, Set<String> addedEdgeIds, int remainingDepth) {
        if (remainingDepth <= 0 || calls == null) return;

        for (MethodNode method : calls) {
            GraphNode methodNode = convertMethodToGraphNode(method);
            addNodeIfNotExists(nodes, addedNodeIds, methodNode);
            addEdgeIfNotExists(edges, addedEdgeIds, createEdge(parentId, method.getId(), "CALLS"));

            if (remainingDepth > 1 && method.getCalls() != null) {
                processMethodCalls(method.getCalls(), method.getId(), nodes, edges, addedNodeIds, addedEdgeIds, remainingDepth - 1);
            }
        }
    }

    private void processCallChain(Set<MethodNode> calls, String callerId, String callerLabel, String callerType,
                                  List<GraphNode> nodes, List<GraphEdge> edges,
                                  List<CallChainResponse.CallChainEntry> callChain,
                                  Set<String> addedNodeIds, Set<String> addedEdgeIds,
                                  int currentDepth, int maxDepth) {
        if (currentDepth > maxDepth || calls == null) return;

        for (MethodNode method : calls) {
            GraphNode methodNode = convertMethodToGraphNode(method);
            addNodeIfNotExists(nodes, addedNodeIds, methodNode);
            addEdgeIfNotExists(edges, addedEdgeIds, createEdge(callerId, method.getId(), "CALLS"));

            callChain.add(CallChainResponse.CallChainEntry.builder()
                    .depth(currentDepth)
                    .callerId(callerId)
                    .callerLabel(callerLabel)
                    .callerType(callerType)
                    .calleeId(method.getId())
                    .calleeLabel(methodNode.getLabel())
                    .calleeType("Method")
                    .lineNumber(method.getLineStart())
                    .relationshipType("CALLS")
                    .build());

            if (currentDepth < maxDepth && method.getCalls() != null) {
                processCallChain(method.getCalls(), method.getId(), methodNode.getLabel(), "Method",
                        nodes, edges, callChain, addedNodeIds, addedEdgeIds, currentDepth + 1, maxDepth);
            }
        }
    }

    private String findServiceForMethod(List<ServiceNode> services, MethodNode method) {
        for (ServiceNode service : services) {
            if (service.getMethods() != null) {
                for (MethodNode m : service.getMethods()) {
                    if (m.getId().equals(method.getId())) {
                        return service.getId();
                    }
                }
            }
        }
        return null;
    }

    private boolean shouldIncludeType(List<String> nodeTypes, String type) {
        return nodeTypes == null || nodeTypes.isEmpty() || nodeTypes.contains(type);
    }

    private void addNodeIfNotExists(List<GraphNode> nodes, Set<String> addedIds, GraphNode node) {
        if (node.getId() != null && !addedIds.contains(node.getId())) {
            nodes.add(node);
            addedIds.add(node.getId());
        }
    }

    private void addEdgeIfNotExists(List<GraphEdge> edges, Set<String> addedIds, GraphEdge edge) {
        if (edge.getId() != null && !addedIds.contains(edge.getId())) {
            edges.add(edge);
            addedIds.add(edge.getId());
        }
    }

    private GraphEdge createEdge(String sourceId, String targetId, String type) {
        String edgeId = sourceId + "-" + type + "-" + targetId;
        return GraphEdge.builder()
                .id(edgeId)
                .source(sourceId)
                .target(targetId)
                .type(type)
                .label(type.toLowerCase().replace("_", " "))
                .style(EdgeStyle.builder()
                        .color(EDGE_COLORS.getOrDefault(type, "#9E9E9E"))
                        .width(2)
                        .arrowShape("triangle")
                        .build())
                .build();
    }

    private GraphMetadata buildMetadata(List<GraphNode> nodes, List<GraphEdge> edges, String projectId, int depth) {
        Map<String, Integer> nodeCountByType = nodes.stream()
                .collect(Collectors.groupingBy(GraphNode::getType, Collectors.summingInt(n -> 1)));
        Map<String, Integer> edgeCountByType = edges.stream()
                .collect(Collectors.groupingBy(GraphEdge::getType, Collectors.summingInt(e -> 1)));

        return GraphMetadata.builder()
                .nodeCount(nodes.size())
                .edgeCount(edges.size())
                .nodeTypes(new ArrayList<>(nodeCountByType.keySet()))
                .edgeTypes(new ArrayList<>(edgeCountByType.keySet()))
                .nodeCountByType(nodeCountByType)
                .edgeCountByType(edgeCountByType)
                .projectId(projectId)
                .depth(depth)
                .build();
    }

    // ==================== Node Conversion Methods ====================

    private GraphNode convertApplicationToGraphNode(ApplicationNode app) {
        Map<String, Object> props = new HashMap<>();
        props.put("mainClassName", app.getMainClassName());
        props.put("mainClassPackage", app.getMainClassPackage());
        props.put("isSpringBoot", app.isSpringBoot());
        props.put("repoUrl", app.getRepoUrl());

        return GraphNode.builder()
                .id(app.getId())
                .label(app.getMainClassName() != null ? app.getMainClassName() : app.getAppKey())
                .type("Application")
                .group(app.getMainClassPackage())
                .properties(props)
                .style(NodeStyle.builder()
                        .color(NODE_COLORS.get("Application"))
                        .shape("circle")
                        .size(50)
                        .icon("application")
                        .build())
                .build();
    }

    private GraphNode convertControllerToGraphNode(ControllerNode controller) {
        Map<String, Object> props = new HashMap<>();
        props.put("className", controller.getClassName());
        props.put("packageName", controller.getPackageName());
        props.put("baseUrl", controller.getBaseUrl());
        props.put("lineStart", controller.getLineStart());
        props.put("lineEnd", controller.getLineEnd());

        return GraphNode.builder()
                .id(controller.getId())
                .label(controller.getClassName())
                .type("Controller")
                .group(controller.getPackageName())
                .properties(props)
                .style(NodeStyle.builder()
                        .color(NODE_COLORS.get("Controller"))
                        .shape("rectangle")
                        .size(35)
                        .icon("controller")
                        .build())
                .build();
    }

    private GraphNode convertEndpointToGraphNode(EndpointNode endpoint) {
        Map<String, Object> props = new HashMap<>();
        props.put("httpMethod", endpoint.getHttpMethod());
        props.put("path", endpoint.getPath());
        props.put("handlerMethod", endpoint.getHandlerMethod());
        props.put("controllerClass", endpoint.getControllerClass());
        props.put("lineStart", endpoint.getLineStart());
        props.put("lineEnd", endpoint.getLineEnd());

        String label = (endpoint.getHttpMethod() != null ? endpoint.getHttpMethod() : "?") + " " +
                       (endpoint.getPath() != null ? endpoint.getPath() : "");

        return GraphNode.builder()
                .id(endpoint.getId())
                .label(label)
                .type("Endpoint")
                .group(endpoint.getControllerClass())
                .properties(props)
                .style(NodeStyle.builder()
                        .color(NODE_COLORS.get("Endpoint"))
                        .shape("rectangle")
                        .size(30)
                        .icon("endpoint")
                        .build())
                .build();
    }

    private GraphNode convertServiceToGraphNode(ServiceNode service) {
        Map<String, Object> props = new HashMap<>();
        props.put("className", service.getClassName());
        props.put("packageName", service.getPackageName());
        props.put("lineStart", service.getLineStart());
        props.put("lineEnd", service.getLineEnd());
        props.put("methodCount", service.getMethods() != null ? service.getMethods().size() : 0);

        return GraphNode.builder()
                .id(service.getId())
                .label(service.getClassName())
                .type("Service")
                .group(service.getPackageName())
                .properties(props)
                .style(NodeStyle.builder()
                        .color(NODE_COLORS.get("Service"))
                        .shape("circle")
                        .size(35)
                        .icon("service")
                        .build())
                .build();
    }

    private GraphNode convertMethodToGraphNode(MethodNode method) {
        Map<String, Object> props = new HashMap<>();
        props.put("className", method.getClassName());
        props.put("methodName", method.getMethodName());
        props.put("signature", method.getSignature());
        props.put("lineStart", method.getLineStart());
        props.put("lineEnd", method.getLineEnd());
        props.put("methodType", method.getMethodType());

        String label = (method.getClassName() != null ? method.getClassName() + "." : "") +
                       (method.getMethodName() != null ? method.getMethodName() : "");

        return GraphNode.builder()
                .id(method.getId())
                .label(label)
                .type("Method")
                .group(method.getClassName())
                .properties(props)
                .style(NodeStyle.builder()
                        .color(NODE_COLORS.get("Method"))
                        .shape("circle")
                        .size(20)
                        .icon("method")
                        .build())
                .build();
    }

    private GraphNode convertRepositoryToGraphNode(RepositoryClassNode repo) {
        Map<String, Object> props = new HashMap<>();
        props.put("className", repo.getClassName());
        props.put("packageName", repo.getPackageName());
        props.put("repositoryType", repo.getRepositoryType());
        props.put("extendsClass", repo.getExtendsClass());
        props.put("lineStart", repo.getLineStart());
        props.put("lineEnd", repo.getLineEnd());

        return GraphNode.builder()
                .id(repo.getId())
                .label(repo.getClassName())
                .type("Repository")
                .group(repo.getPackageName())
                .properties(props)
                .style(NodeStyle.builder()
                        .color(NODE_COLORS.get("Repository"))
                        .shape("rectangle")
                        .size(30)
                        .icon("repository")
                        .build())
                .build();
    }

    private GraphNode convertDatabaseTableToGraphNode(DatabaseTableNode table) {
        Map<String, Object> props = new HashMap<>();
        props.put("tableName", table.getTableName());
        props.put("entityClass", table.getEntityClass());
        props.put("databaseType", table.getDatabaseType());
        props.put("operations", table.getOperations());

        return GraphNode.builder()
                .id(table.getId())
                .label(table.getTableName() != null ? table.getTableName() : table.getEntitySimpleName())
                .type("DatabaseTable")
                .group("database")
                .properties(props)
                .style(NodeStyle.builder()
                        .color(NODE_COLORS.get("DatabaseTable"))
                        .shape("rectangle")
                        .size(25)
                        .icon("database")
                        .build())
                .build();
    }

    private GraphNode convertKafkaListenerToGraphNode(KafkaListenerNode listener) {
        Map<String, Object> props = new HashMap<>();
        props.put("className", listener.getClassName());
        props.put("packageName", listener.getPackageName());
        props.put("lineStart", listener.getLineStart());
        props.put("lineEnd", listener.getLineEnd());

        return GraphNode.builder()
                .id(listener.getId())
                .label(listener.getClassName())
                .type("KafkaListener")
                .group(listener.getPackageName())
                .properties(props)
                .style(NodeStyle.builder()
                        .color(NODE_COLORS.get("KafkaListener"))
                        .shape("hexagon")
                        .size(30)
                        .icon("kafka-listener")
                        .build())
                .build();
    }
}
