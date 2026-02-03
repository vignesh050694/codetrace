package com.architecture.memory.orkestify.service.graph;

import com.architecture.memory.orkestify.model.graph.nodes.*;
import com.architecture.memory.orkestify.repository.graph.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for querying architecture insights from the Neo4j graph database.
 * Provides methods for analyzing code structure, dependencies, and communication patterns.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GraphQueryService {

    private final ApplicationNodeRepository applicationNodeRepository;
    private final ControllerNodeRepository controllerNodeRepository;
    private final EndpointNodeRepository endpointNodeRepository;
    private final ServiceNodeRepository serviceNodeRepository;
    private final MethodNodeRepository methodNodeRepository;
    private final RepositoryClassNodeRepository repositoryClassNodeRepository;
    private final KafkaTopicNodeRepository kafkaTopicNodeRepository;
    private final KafkaListenerNodeRepository kafkaListenerNodeRepository;

    // ========================= Application Queries =========================

    /**
     * Get all applications in a project.
     */
    public List<ApplicationNode> getApplicationsByProject(String projectId) {
        return applicationNodeRepository.findByProjectId(projectId);
    }

    /**
     * Get an application with its full graph (controllers, services, repositories).
     */
    public Optional<ApplicationNode> getApplicationWithGraph(String appKey) {
        return applicationNodeRepository.findByAppKeyWithFullGraph(appKey);
    }

    // ========================= Endpoint Queries =========================

    /**
     * Get all endpoints in a project.
     */
    public List<EndpointNode> getEndpointsByProject(String projectId) {
        return endpointNodeRepository.findByProjectId(projectId);
    }

    /**
     * Get all endpoints grouped by controller.
     */
    public Map<String, List<EndpointNode>> getEndpointsGroupedByController(String projectId) {
        List<EndpointNode> endpoints = endpointNodeRepository.findByProjectId(projectId);
        return endpoints.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getControllerClass() != null ? e.getControllerClass() : "Unknown"
                ));
    }

    /**
     * Get endpoints by HTTP method.
     */
    public List<EndpointNode> getEndpointsByHttpMethod(String projectId, String httpMethod) {
        return endpointNodeRepository.findByProjectIdAndHttpMethod(projectId, httpMethod);
    }

    /**
     * Search endpoints by path pattern.
     */
    public List<EndpointNode> searchEndpointsByPath(String projectId, String pathPattern) {
        // Convert glob-style pattern to regex
        String regex = pathPattern.replace("*", ".*");
        return endpointNodeRepository.findByPathPattern(projectId, regex);
    }

    // ========================= Service Queries =========================

    /**
     * Get all services in a project.
     */
    public List<ServiceNode> getServicesByProject(String projectId) {
        return serviceNodeRepository.findByProjectId(projectId);
    }

    /**
     * Get services with their methods.
     */
    public List<ServiceNode> getServicesWithMethods(String projectId) {
        return serviceNodeRepository.findByProjectIdWithMethods(projectId);
    }

    /**
     * Get service dependencies (which services call which other services).
     */
    public Map<String, Set<String>> getServiceDependencies(String projectId) {
        List<Object[]> rawDependencies = serviceNodeRepository.findServiceDependencies(projectId);
        Map<String, Set<String>> dependencies = new HashMap<>();

        for (Object[] row : rawDependencies) {
            String serviceName = (String) row[0];
            @SuppressWarnings("unchecked")
            List<String> deps = (List<String>) row[1];
            dependencies.put(serviceName, new HashSet<>(deps));
        }

        return dependencies;
    }

    // ========================= Method Call Queries =========================

    /**
     * Find all callers of a specific method.
     */
    public List<MethodNode> findCallersOfMethod(String projectId, String methodSignature) {
        return methodNodeRepository.findCallersOf(projectId, methodSignature);
    }

    /**
     * Find all methods called by a specific method.
     */
    public List<MethodNode> findCalleesOfMethod(String projectId, String methodSignature) {
        return methodNodeRepository.findCalleesOf(projectId, methodSignature);
    }

    /**
     * Find call paths from endpoints to a specific method.
     */
    public List<Object> findCallPathsToMethod(String projectId, String methodSignature) {
        return methodNodeRepository.findCallPathsToMethod(projectId, methodSignature);
    }

    // ========================= Repository Queries =========================

    /**
     * Get all repositories in a project.
     */
    public List<RepositoryClassNode> getRepositoriesByProject(String projectId) {
        return repositoryClassNodeRepository.findByProjectId(projectId);
    }

    /**
     * Get repositories with their database tables.
     */
    public List<RepositoryClassNode> getRepositoriesWithTables(String projectId) {
        return repositoryClassNodeRepository.findByProjectIdWithDatabaseTables(projectId);
    }

    /**
     * Get repositories by type (JPA, MongoDB, Reactive).
     */
    public List<RepositoryClassNode> getRepositoriesByType(String projectId, String repositoryType) {
        return repositoryClassNodeRepository.findByProjectIdAndRepositoryType(projectId, repositoryType);
    }

    // ========================= Kafka Queries =========================

    /**
     * Get all Kafka topics in a project with their producers and consumers.
     */
    public List<KafkaTopicNode> getKafkaTopicsWithConnections(String projectId) {
        return kafkaTopicNodeRepository.findAllByProjectIdWithProducersAndConsumers(projectId);
    }

    /**
     * Get Kafka listeners in a project.
     */
    public List<KafkaListenerNode> getKafkaListenersByProject(String projectId) {
        return kafkaListenerNodeRepository.findByProjectId(projectId);
    }

    /**
     * Get Kafka flow map (topic -> producers/consumers).
     */
    public Map<String, Map<String, List<String>>> getKafkaFlowMap(String projectId) {
        List<KafkaTopicNode> topics = kafkaTopicNodeRepository.findAllByProjectIdWithProducersAndConsumers(projectId);
        Map<String, Map<String, List<String>>> flowMap = new HashMap<>();

        for (KafkaTopicNode topic : topics) {
            Map<String, List<String>> topicInfo = new HashMap<>();
            // Note: Producers and consumers would need to be extracted from the query results
            // This is a simplified version
            topicInfo.put("producers", new ArrayList<>());
            topicInfo.put("consumers", new ArrayList<>());
            flowMap.put(topic.getName(), topicInfo);
        }

        return flowMap;
    }

    // ========================= External Call Queries =========================

    /**
     * Get endpoints that make external API calls.
     */
    public List<EndpointNode> getEndpointsWithExternalCalls(String projectId) {
        return endpointNodeRepository.findByProjectIdWithExternalCalls(projectId);
    }

    /**
     * Get methods that make external API calls.
     */
    public List<MethodNode> getMethodsWithExternalCalls(String projectId) {
        return methodNodeRepository.findMethodsWithExternalCalls(projectId);
    }

    // ========================= Architecture Overview =========================

    /**
     * Get a summary of the project architecture.
     */
    public Map<String, Object> getArchitectureSummary(String projectId) {
        Map<String, Object> summary = new HashMap<>();

        List<ApplicationNode> apps = applicationNodeRepository.findByProjectId(projectId);
        summary.put("totalApplications", apps.size());

        List<ControllerNode> controllers = controllerNodeRepository.findByProjectId(projectId);
        summary.put("totalControllers", controllers.size());

        List<EndpointNode> endpoints = endpointNodeRepository.findByProjectId(projectId);
        summary.put("totalEndpoints", endpoints.size());

        // Group endpoints by HTTP method
        Map<String, Long> endpointsByMethod = endpoints.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getHttpMethod() != null ? e.getHttpMethod() : "UNKNOWN",
                        Collectors.counting()
                ));
        summary.put("endpointsByMethod", endpointsByMethod);

        List<ServiceNode> services = serviceNodeRepository.findByProjectId(projectId);
        summary.put("totalServices", services.size());

        List<RepositoryClassNode> repositories = repositoryClassNodeRepository.findByProjectId(projectId);
        summary.put("totalRepositories", repositories.size());

        // Group repositories by type
        Map<String, Long> repositoriesByType = repositories.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getRepositoryType() != null ? r.getRepositoryType() : "Unknown",
                        Collectors.counting()
                ));
        summary.put("repositoriesByType", repositoriesByType);

        List<KafkaTopicNode> topics = kafkaTopicNodeRepository.findByProjectId(projectId);
        summary.put("totalKafkaTopics", topics.size());

        List<KafkaListenerNode> listeners = kafkaListenerNodeRepository.findByProjectId(projectId);
        summary.put("totalKafkaListeners", listeners.size());

        return summary;
    }

    /**
     * Get application breakdown (stats per application in a project).
     */
    public List<Map<String, Object>> getApplicationBreakdown(String projectId) {
        List<ApplicationNode> apps = applicationNodeRepository.findByProjectIdWithRelationships(projectId);
        List<Map<String, Object>> breakdown = new ArrayList<>();

        for (ApplicationNode app : apps) {
            Map<String, Object> appStats = new HashMap<>();
            appStats.put("appKey", app.getAppKey());
            appStats.put("mainClassName", app.getMainClassName());
            appStats.put("isSpringBoot", app.isSpringBoot());
            appStats.put("repoUrl", app.getRepoUrl());
            appStats.put("controllersCount", app.getControllers() != null ? app.getControllers().size() : 0);
            appStats.put("servicesCount", app.getServices() != null ? app.getServices().size() : 0);
            appStats.put("repositoriesCount", app.getRepositories() != null ? app.getRepositories().size() : 0);
            appStats.put("kafkaListenersCount", app.getKafkaListeners() != null ? app.getKafkaListeners().size() : 0);
            appStats.put("configurationsCount", app.getConfigurations() != null ? app.getConfigurations().size() : 0);
            breakdown.add(appStats);
        }

        return breakdown;
    }
}
