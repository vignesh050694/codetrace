package com.architecture.memory.orkestify.service.graph;

import com.architecture.memory.orkestify.model.graph.nodes.*;
import com.architecture.memory.orkestify.repository.graph.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for resolving cross-application relationships in the Neo4j graph.
 * This includes:
 * - Linking external API calls to their target endpoints (cross-microservice)
 * - Connecting Kafka producers and consumers across applications
 * - Extracting target service names from URLs
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GraphResolutionService {

    private final EndpointNodeRepository endpointNodeRepository;
    private final MethodNodeRepository methodNodeRepository;
    private final KafkaTopicNodeRepository kafkaTopicNodeRepository;
    private final ApplicationNodeRepository applicationNodeRepository;

    private static final Pattern HOST_PATTERN = Pattern.compile("https?://([^:/]+)");

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

        int resolvedCount = 0;

        // Resolve from methods with external calls
        List<MethodNode> methodsWithExternalCalls = methodNodeRepository.findMethodsWithExternalCalls(projectId);
        for (MethodNode method : methodsWithExternalCalls) {
            if (method.getExternalCalls() != null) {
                for (ExternalCallNode externalCall : method.getExternalCalls()) {
                    if (resolveExternalCall(externalCall, allEndpoints)) {
                        resolvedCount++;
                    }
                }
            }
        }

        // Resolve from endpoints with external calls
        List<EndpointNode> endpointsWithExternalCalls = endpointNodeRepository.findByProjectIdWithExternalCalls(projectId);
        for (EndpointNode endpoint : endpointsWithExternalCalls) {
            if (endpoint.getExternalCalls() != null) {
                for (ExternalCallNode externalCall : endpoint.getExternalCalls()) {
                    if (resolveExternalCall(externalCall, allEndpoints)) {
                        resolvedCount++;
                    }
                }
            }
        }

        // Save resolved nodes
        if (resolvedCount > 0) {
            for (MethodNode method : methodsWithExternalCalls) {
                methodNodeRepository.save(method);
            }
            for (EndpointNode endpoint : endpointsWithExternalCalls) {
                endpointNodeRepository.save(endpoint);
            }
        }

        log.info("Resolved {} external API calls for project: {}", resolvedCount, projectId);
    }

    /**
     * Attempt to resolve a single external call to a matching endpoint.
     */
    private boolean resolveExternalCall(ExternalCallNode externalCall, List<EndpointNode> allEndpoints) {
        if (externalCall.isResolved() || externalCall.getUrl() == null) {
            return false;
        }

        // Extract service name from URL (even if we can't match the endpoint)
        String serviceName = extractServiceNameFromUrl(externalCall.getUrl());
        if (serviceName != null && externalCall.getTargetService() == null) {
            externalCall.setTargetService(serviceName);
        }

        Optional<EndpointNode> matchedEndpoint = findMatchingEndpoint(
                allEndpoints, externalCall.getUrl(), externalCall.getHttpMethod());

        if (matchedEndpoint.isPresent()) {
            EndpointNode target = matchedEndpoint.get();
            externalCall.setResolved(true);
            externalCall.setTargetEndpointNode(target);
            externalCall.setTargetControllerClass(target.getControllerClass());
            externalCall.setTargetHandlerMethod(target.getHandlerMethod());
            externalCall.setTargetEndpoint(target.getFullPath());
            externalCall.setResolutionReason("Matched endpoint in project graph");
            log.debug("Resolved external call {} {} -> {}.{}()",
                    externalCall.getHttpMethod(), externalCall.getUrl(),
                    target.getControllerClass(), target.getHandlerMethod());
            return true;
        }

        return false;
    }

    /**
     * Find a matching endpoint for a given URL and HTTP method.
     * Uses multiple matching strategies: exact, pattern, suffix, and partial path matching.
     */
    private Optional<EndpointNode> findMatchingEndpoint(List<EndpointNode> endpoints, String url, String httpMethod) {
        if (url == null || "<dynamic>".equals(url)) {
            return Optional.empty();
        }

        String normalizedUrl = normalizeUrl(url);
        if (normalizedUrl.isEmpty()) {
            return Optional.empty();
        }

        EndpointNode bestMatch = null;
        int bestScore = 0;

        for (EndpointNode endpoint : endpoints) {
            if (endpoint.getFullPath() == null) continue;

            // Check HTTP method match (if specified and known)
            if (httpMethod != null && !"UNKNOWN".equals(httpMethod) && !"REQUEST".equals(httpMethod)
                    && endpoint.getHttpMethod() != null
                    && !httpMethod.equalsIgnoreCase(endpoint.getHttpMethod())) {
                continue;
            }

            String endpointPath = normalizeUrl(endpoint.getFullPath());
            int score = calculateMatchScore(normalizedUrl, endpointPath);

            if (score > bestScore) {
                bestScore = score;
                bestMatch = endpoint;
            }
        }

        // Require a minimum score for a match
        if (bestScore >= 3) {
            return Optional.of(bestMatch);
        }

        return Optional.empty();
    }

    /**
     * Calculate a match score between a URL and an endpoint path.
     * Higher score = better match. 0 = no match.
     */
    private int calculateMatchScore(String url, String endpointPath) {
        // Exact match = highest score
        if (url.equals(endpointPath)) {
            return 10;
        }

        // Pattern match with path variables
        if (matchesPathPattern(url, endpointPath)) {
            return 8;
        }

        // URL ends with endpoint path (e.g., http://host/api/users matches /api/users)
        if (url.endsWith(endpointPath)) {
            return 7;
        }

        // Endpoint path ends with URL path (partial path match)
        if (endpointPath.endsWith(url)) {
            return 5;
        }

        // Check if the path segments match from the end
        String[] urlParts = url.split("/");
        String[] endpointParts = endpointPath.split("/");

        if (urlParts.length >= 2 && endpointParts.length >= 2) {
            // Compare last N segments
            int matching = 0;
            int minLen = Math.min(urlParts.length, endpointParts.length);
            for (int i = 1; i <= minLen; i++) {
                String urlPart = urlParts[urlParts.length - i];
                String endpointPart = endpointParts[endpointParts.length - i];

                if (urlPart.equals(endpointPart)) {
                    matching++;
                } else if (endpointPart.startsWith("{") && endpointPart.endsWith("}")) {
                    matching++; // Path variable matches anything
                } else {
                    break;
                }
            }

            if (matching >= 2) {
                return matching + 1; // At least 3 if 2+ segments match
            }
        }

        return 0;
    }

    /**
     * Normalize a URL for comparison - strips protocol, host, port, query params.
     */
    private String normalizeUrl(String url) {
        if (url == null) return "";

        String normalized = url;

        // Remove protocol and host if present
        if (normalized.contains("://")) {
            int pathStart = normalized.indexOf("/", normalized.indexOf("://") + 3);
            if (pathStart > 0) {
                normalized = normalized.substring(pathStart);
            } else {
                return ""; // URL with host but no path
            }
        }

        // Remove query parameters
        int queryStart = normalized.indexOf("?");
        if (queryStart > 0) {
            normalized = normalized.substring(0, queryStart);
        }

        // Remove fragment
        int fragmentStart = normalized.indexOf("#");
        if (fragmentStart > 0) {
            normalized = normalized.substring(0, fragmentStart);
        }

        // Remove trailing slash
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        // Remove <dynamic> prefix if present (e.g., <dynamic>/api/users)
        if (normalized.startsWith("<dynamic>")) {
            normalized = normalized.substring("<dynamic>".length());
        }

        return normalized;
    }

    /**
     * Check if a URL matches a path pattern with variables.
     */
    private boolean matchesPathPattern(String url, String pattern) {
        String regex = pattern
                .replaceAll("\\{[^}]+\\}", "[^/]+")  // {id} -> [^/]+
                .replaceAll("\\*\\*", ".*");          // ** -> .*

        regex = "^" + Pattern.quote(regex)
                .replace("\\[^/\\]+", "[^/]+")
                .replace("\\.\\*", ".*") + "$";

        // Simpler approach: build regex manually
        try {
            String simpleRegex = "^" + pattern
                    .replaceAll("\\{[^}]+\\}", "[^/]+")
                    .replace("**", ".*") + "$";
            return url.matches(simpleRegex);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extract service name from URL hostname.
     * E.g., "http://user-service:8080/api/users" -> "user-service"
     *        "http://order-service.default.svc/api/orders" -> "order-service"
     */
    private String extractServiceNameFromUrl(String url) {
        if (url == null || "<dynamic>".equals(url)) {
            return null;
        }

        Matcher matcher = HOST_PATTERN.matcher(url);
        if (matcher.find()) {
            String host = matcher.group(1);

            // Skip localhost and IP addresses
            if ("localhost".equals(host) || "127.0.0.1".equals(host) || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                return null;
            }

            // Remove K8s/DNS suffixes
            if (host.contains(".")) {
                host = host.split("\\.")[0];
            }

            return host;
        }

        return null;
    }

    /**
     * Resolve Kafka producer-consumer connections within a project.
     */
    @Transactional
    public void resolveKafkaConnections(String projectId) {
        log.info("Resolving Kafka connections for project: {}", projectId);

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
