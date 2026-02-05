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
    private final KafkaListenerNodeRepository kafkaListenerNodeRepository;
    private final ApplicationNodeRepository applicationNodeRepository;
    private final ExternalCallNodeRepository externalCallNodeRepository;

    private static final Pattern HOST_PATTERN = Pattern.compile("https?://([^:/]+)");

    /**
     * Resolve external API calls within a project.
     * Links ExternalCallNodes to their target EndpointNodes when the target is within the same project.
     */
    @Transactional
    public void resolveExternalCalls(String projectId) {
        log.info("[external-resolution] start projectId={} ", projectId);

        // Gather endpoints and build map of id -> normalized path
        List<EndpointNode> allEndpoints = endpointNodeRepository.findByProjectId(projectId);
        log.info("[external-resolution] endpoints={} ", allEndpoints.size());
        Map<String, String> endpointPathById = new HashMap<>();
        for (EndpointNode endpoint : allEndpoints) {
            if (endpoint.getId() != null && endpoint.getFullPath() != null) {
                endpointPathById.put(endpoint.getId(), normalizeUrl(endpoint.getFullPath()));
            }
        }

        // Gather all external calls directly from the graph
        List<ExternalCallNode> externalCalls = externalCallNodeRepository.findByProjectId(projectId);
        log.info("[external-resolution] externalCalls={} ", externalCalls.size());
        int resolvedCount = 0;
        int skippedDynamic = 0;

        for (ExternalCallNode externalCall : externalCalls) {
            if (externalCall.isResolved()) continue;
            String url = externalCall.getUrl();
            if (url == null || url.isBlank() || "<dynamic>".equals(url)) {
                skippedDynamic++;
                continue;
            }

            String normalizedUrl = normalizeUrl(url);
            if (normalizedUrl.isEmpty()) {
                skippedDynamic++;
                continue;
            }

            List<String> urlCandidates = buildUrlCandidates(normalizedUrl);
            EndpointNode bestEndpoint = null;
            int bestScore = 0;

            for (Map.Entry<String, String> entry : endpointPathById.entrySet()) {
                String endpointId = entry.getKey();
                String endpointPath = entry.getValue();
                int score = 0;
                for (String candidate : urlCandidates) {
                    score = Math.max(score, calculateMatchScore(candidate, endpointPath));
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestEndpoint = allEndpoints.stream()
                            .filter(e -> endpointId.equals(e.getId()))
                            .findFirst()
                            .orElse(null);
                }
            }

            if (bestEndpoint != null && bestScore >= 3) {
                externalCall.setResolved(true);
                externalCall.setTargetEndpointNode(bestEndpoint);
                externalCall.setTargetControllerClass(bestEndpoint.getControllerClass());
                externalCall.setTargetHandlerMethod(bestEndpoint.getHandlerMethod());
                externalCall.setTargetEndpoint(bestEndpoint.getFullPath());
                externalCall.setResolutionReason("Matched endpoint via scoring");
                resolvedCount++;
            }
        }

        if (resolvedCount > 0) {
            externalCallNodeRepository.saveAll(externalCalls);
        }

        log.info("[external-resolution] end projectId={} resolved={} skippedDynamic={} endpoints={} externalCalls={} ",
                projectId, resolvedCount, skippedDynamic, allEndpoints.size(), externalCalls.size());
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

        List<String> urlCandidates = buildUrlCandidates(normalizedUrl);

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
            int score = 0;
            for (String candidate : urlCandidates) {
                score = Math.max(score, calculateMatchScore(candidate, endpointPath));
            }

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

    private List<String> buildUrlCandidates(String normalizedUrl) {
        List<String> candidates = new ArrayList<>();
        if (normalizedUrl == null || normalizedUrl.isEmpty()) return candidates;

        String cleaned = normalizedUrl;
        if (!cleaned.startsWith("/")) {
            cleaned = "/" + cleaned;
        }
        candidates.add(cleaned);

        // Remove gateway/base path segments (e.g., /user-service//student/{id} -> /student/{id})
        String[] parts = cleaned.split("/");
        List<String> segments = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            segments.add(part);
        }

        if (segments.size() >= 2) {
            candidates.add("/" + String.join("/", segments.subList(1, segments.size())));
        }
        if (segments.size() >= 3) {
            candidates.add("/" + String.join("/", segments.subList(2, segments.size())));
        }

        return candidates;
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

        String normalized = url.trim();

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

        // Remove <dynamic> tokens anywhere in path
        normalized = normalized.replace("<dynamic>", "");

        // Collapse multiple slashes
        normalized = normalized.replaceAll("/{2,}", "/");

        // Remove trailing slash
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        // Ensure leading slash for path-only comparison
        if (!normalized.isEmpty() && !normalized.startsWith("/")) {
            normalized = "/" + normalized;
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
     * Populates each KafkaTopicNode with producer/consumer service+method details.
     */
    @Transactional
    public void resolveKafkaConnections(String projectId) {
        log.info("Resolving Kafka connections for project: {}", projectId);

        // Fetch all topics
        List<KafkaTopicNode> topics = kafkaTopicNodeRepository.findByProjectId(projectId);
        if (topics.isEmpty()) {
            log.info("No Kafka topics found for project: {}", projectId);
            return;
        }

        // Fetch producer details: which service.method produces to each topic
        List<Map<String, Object>> producerRows = kafkaTopicNodeRepository.findProducerDetailsForProject(projectId);
        Map<String, List<String>> producerDetailsByTopic = new HashMap<>();
        Map<String, Set<String>> producerServicesByTopic = new HashMap<>();

        for (Map<String, Object> row : producerRows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = row.containsKey("result") ? (Map<String, Object>) row.get("result") : row;

            String topicName = (String) result.get("topicName");
            String className = (String) result.get("className");
            String methodName = (String) result.get("methodName");

            if (topicName != null && className != null) {
                String detail = methodName != null ? className + "." + methodName + "()" : className;
                producerDetailsByTopic.computeIfAbsent(topicName, k -> new ArrayList<>()).add(detail);
                producerServicesByTopic.computeIfAbsent(topicName, k -> new LinkedHashSet<>()).add(className);
            }
        }

        // Fetch consumer details: which listener.method consumes from each topic
        List<Map<String, Object>> consumerRows = kafkaTopicNodeRepository.findConsumerDetailsForProject(projectId);
        Map<String, List<String>> consumerDetailsByTopic = new HashMap<>();
        Map<String, Set<String>> consumerServicesByTopic = new HashMap<>();

        for (Map<String, Object> row : consumerRows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = row.containsKey("result") ? (Map<String, Object>) row.get("result") : row;

            String topicName = (String) result.get("topicName");
            String className = (String) result.get("className");
            String methodName = (String) result.get("methodName");
            String groupId = (String) result.get("groupId");

            if (topicName != null && className != null) {
                String detail = methodName != null ? className + "." + methodName + "()" : className;
                if (groupId != null && !groupId.isEmpty()) {
                    detail += " [group: " + groupId + "]";
                }
                consumerDetailsByTopic.computeIfAbsent(topicName, k -> new ArrayList<>()).add(detail);
                consumerServicesByTopic.computeIfAbsent(topicName, k -> new LinkedHashSet<>()).add(className);
            }
        }

        // Update each topic node with resolved producer/consumer details
        int resolvedCount = 0;
        for (KafkaTopicNode topic : topics) {
            String name = topic.getName();
            boolean updated = false;

            List<String> producers = producerDetailsByTopic.getOrDefault(name, List.of());
            if (!producers.isEmpty()) {
                topic.setProducerDetails(new ArrayList<>(producers));
                topic.setProducerServiceNames(new ArrayList<>(
                        producerServicesByTopic.getOrDefault(name, Set.of())));
                updated = true;
            }

            List<String> consumers = consumerDetailsByTopic.getOrDefault(name, List.of());
            if (!consumers.isEmpty()) {
                topic.setConsumerDetails(new ArrayList<>(consumers));
                topic.setConsumerServiceNames(new ArrayList<>(
                        consumerServicesByTopic.getOrDefault(name, Set.of())));
                updated = true;
            }

            if (updated) {
                kafkaTopicNodeRepository.save(topic);
                resolvedCount++;
                log.info("Kafka topic '{}': {} producer(s) [{}], {} consumer(s) [{}]",
                        name, producers.size(),
                        String.join(", ", topic.getProducerServiceNames()),
                        consumers.size(),
                        String.join(", ", topic.getConsumerServiceNames()));
            }
        }

        // Log orphan topics
        List<KafkaTopicNode> orphanProducers = kafkaTopicNodeRepository.findTopicsWithoutConsumers(projectId);
        for (KafkaTopicNode orphan : orphanProducers) {
            log.warn("Kafka topic '{}' has producers but no consumers", orphan.getName());
        }
        List<KafkaTopicNode> orphanConsumers = kafkaTopicNodeRepository.findTopicsWithoutProducers(projectId);
        for (KafkaTopicNode orphan : orphanConsumers) {
            log.warn("Kafka topic '{}' has consumers but no producers", orphan.getName());
        }

        log.info("Resolved Kafka connections for {} topics in project: {}", resolvedCount, projectId);
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
