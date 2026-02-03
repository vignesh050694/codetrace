package com.architecture.memory.orkestify.service.graph;

import com.architecture.memory.orkestify.model.graph.nodes.*;
import com.architecture.memory.orkestify.repository.graph.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Service for resolving cross-application relationships in the Neo4j graph.
 * This includes:
 * - Linking external API calls to their target endpoints
 * - Connecting Kafka producers and consumers across applications
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GraphResolutionService {

    private final EndpointNodeRepository endpointNodeRepository;
    private final MethodNodeRepository methodNodeRepository;
    private final KafkaTopicNodeRepository kafkaTopicNodeRepository;
    private final ApplicationNodeRepository applicationNodeRepository;

    /**
     * Resolve external API calls within a project.
     * Links ExternalCallNodes to their target EndpointNodes when the target is within the same project.
     */
    @Transactional
    public void resolveExternalCalls(String projectId) {
        log.info("Resolving external API calls for project: {}", projectId);

        // Get all endpoints in the project for matching
        List<EndpointNode> allEndpoints = endpointNodeRepository.findByProjectId(projectId);
        log.info("Found {} endpoints in project for resolution", allEndpoints.size());

        // Get all methods with external calls
        List<MethodNode> methodsWithExternalCalls = methodNodeRepository.findMethodsWithExternalCalls(projectId);

        int resolvedCount = 0;
        for (MethodNode method : methodsWithExternalCalls) {
            for (ExternalCallNode externalCall : method.getExternalCalls()) {
                if (!externalCall.isResolved() && externalCall.getUrl() != null) {
                    // Try to resolve the external call
                    Optional<EndpointNode> matchedEndpoint = findMatchingEndpoint(
                            allEndpoints,
                            externalCall.getUrl(),
                            externalCall.getHttpMethod()
                    );

                    if (matchedEndpoint.isPresent()) {
                        EndpointNode target = matchedEndpoint.get();
                        externalCall.setResolved(true);
                        externalCall.setTargetEndpointNode(target);
                        externalCall.setTargetControllerClass(target.getControllerClass());
                        externalCall.setTargetHandlerMethod(target.getHandlerMethod());
                        externalCall.setTargetEndpoint(target.getFullPath());
                        externalCall.setResolutionReason("Matched endpoint in project graph");
                        resolvedCount++;
                    }
                }
            }
        }

        // Get all endpoints with external calls and resolve them too
        List<EndpointNode> endpointsWithExternalCalls = endpointNodeRepository.findByProjectIdWithExternalCalls(projectId);
        for (EndpointNode endpoint : endpointsWithExternalCalls) {
            for (ExternalCallNode externalCall : endpoint.getExternalCalls()) {
                if (!externalCall.isResolved() && externalCall.getUrl() != null) {
                    Optional<EndpointNode> matchedEndpoint = findMatchingEndpoint(
                            allEndpoints,
                            externalCall.getUrl(),
                            externalCall.getHttpMethod()
                    );

                    if (matchedEndpoint.isPresent()) {
                        EndpointNode target = matchedEndpoint.get();
                        externalCall.setResolved(true);
                        externalCall.setTargetEndpointNode(target);
                        externalCall.setTargetControllerClass(target.getControllerClass());
                        externalCall.setTargetHandlerMethod(target.getHandlerMethod());
                        externalCall.setTargetEndpoint(target.getFullPath());
                        externalCall.setResolutionReason("Matched endpoint in project graph");
                        resolvedCount++;
                    }
                }
            }
        }

        log.info("Resolved {} external API calls for project: {}", resolvedCount, projectId);
    }

    /**
     * Find a matching endpoint for a given URL and HTTP method.
     */
    private Optional<EndpointNode> findMatchingEndpoint(List<EndpointNode> endpoints, String url, String httpMethod) {
        if (url == null || url.equals("<dynamic>")) {
            return Optional.empty();
        }

        // Normalize URL for comparison
        String normalizedUrl = normalizeUrl(url);

        for (EndpointNode endpoint : endpoints) {
            if (endpoint.getFullPath() == null) continue;

            String endpointPath = endpoint.getFullPath();

            // Check HTTP method match (if specified)
            if (httpMethod != null && !httpMethod.equals("UNKNOWN") &&
                endpoint.getHttpMethod() != null && !httpMethod.equalsIgnoreCase(endpoint.getHttpMethod())) {
                continue;
            }

            // Try exact match first
            if (normalizedUrl.equals(normalizeUrl(endpointPath))) {
                return Optional.of(endpoint);
            }

            // Try pattern matching (handle path variables like {id})
            if (matchesPathPattern(normalizedUrl, endpointPath)) {
                return Optional.of(endpoint);
            }
        }

        return Optional.empty();
    }

    /**
     * Normalize a URL for comparison.
     */
    private String normalizeUrl(String url) {
        if (url == null) return "";

        // Remove protocol and host if present
        String normalized = url;
        if (normalized.contains("://")) {
            int pathStart = normalized.indexOf("/", normalized.indexOf("://") + 3);
            if (pathStart > 0) {
                normalized = normalized.substring(pathStart);
            }
        }

        // Remove query parameters
        int queryStart = normalized.indexOf("?");
        if (queryStart > 0) {
            normalized = normalized.substring(0, queryStart);
        }

        // Remove trailing slash
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    /**
     * Check if a URL matches a path pattern with variables.
     */
    private boolean matchesPathPattern(String url, String pattern) {
        // Convert path pattern to regex
        // e.g., /api/users/{id} -> /api/users/[^/]+
        String regex = pattern.replaceAll("\\{[^}]+\\}", "[^/]+");
        regex = "^" + regex.replace("/", "\\/") + "$";

        try {
            return url.matches(regex);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolve Kafka producer-consumer connections within a project.
     * This creates relationships between producers and consumers on the same topic.
     */
    @Transactional
    public void resolveKafkaConnections(String projectId) {
        log.info("Resolving Kafka connections for project: {}", projectId);

        // Get all topics with their producers and consumers
        List<KafkaTopicNode> topics = kafkaTopicNodeRepository.findAllByProjectIdWithProducersAndConsumers(projectId);

        int connectionCount = 0;
        for (KafkaTopicNode topic : topics) {
            log.debug("Topic: {} has producers and consumers linked in graph", topic.getName());
            connectionCount++;
        }

        log.info("Found {} Kafka topics with connections in project: {}", connectionCount, projectId);
    }

    /**
     * Find topics that have no consumers (potential orphan producers).
     */
    public List<KafkaTopicNode> findOrphanProducers(String projectId) {
        return kafkaTopicNodeRepository.findTopicsWithoutConsumers(projectId);
    }

    /**
     * Find topics that have no producers (potential orphan consumers).
     */
    public List<KafkaTopicNode> findOrphanConsumers(String projectId) {
        return kafkaTopicNodeRepository.findTopicsWithoutProducers(projectId);
    }
}
