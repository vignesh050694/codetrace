package com.architecture.memory.orkestify.service.graph;

import com.architecture.memory.orkestify.dto.graph.*;
import com.architecture.memory.orkestify.model.graph.nodes.*;
import com.architecture.memory.orkestify.repository.graph.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
                .filter(r -> r.getAccessesTable() != null)
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

        List<ApplicationNode> apps = applicationNodeRepository.findByProjectIdWithRelationships(projectId);

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
                    if (repo.getAccessesTable() != null && shouldIncludeType(nodeTypes, "DatabaseTable")) {
                        GraphNode tableNode = convertDatabaseTableToGraphNode(repo.getAccessesTable());
                        addNodeIfNotExists(nodes, addedNodeIds, tableNode);
                        addEdgeIfNotExists(edges, addedEdgeIds, createEdge(repo.getId(), repo.getAccessesTable().getId(), "ACCESSES"));
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

            if (repo.getAccessesTable() != null) {
                GraphNode tableNode = convertDatabaseTableToGraphNode(repo.getAccessesTable());
                addNodeIfNotExists(nodes, addedNodeIds, tableNode);
                addEdgeIfNotExists(edges, addedEdgeIds, createEdge(repo.getId(), repo.getAccessesTable().getId(), "ACCESSES"));
            }
        }

        GraphMetadata metadata = buildMetadata(nodes, edges, projectId, 1);
        return GraphVisualizationResponse.builder()
                .nodes(nodes)
                .edges(edges)
                .metadata(metadata)
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
