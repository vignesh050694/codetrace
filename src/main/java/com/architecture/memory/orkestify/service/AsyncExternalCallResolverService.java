package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.EndpointRegistry;
import com.architecture.memory.orkestify.dto.ExternalCallInfo;
import com.architecture.memory.orkestify.dto.KafkaTopicRegistry;
import com.architecture.memory.orkestify.model.graph.nodes.EndpointNode;
import com.architecture.memory.orkestify.model.graph.nodes.ExternalCallNode;
import com.architecture.memory.orkestify.model.graph.nodes.KafkaTopicNode;
import com.architecture.memory.orkestify.repository.graph.EndpointNodeRepository;
import com.architecture.memory.orkestify.repository.graph.ExternalCallNodeRepository;
import com.architecture.memory.orkestify.repository.graph.KafkaTopicNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for asynchronously resolving external API calls and Kafka topics in the graph.
 *
 * <h3>External Call Relationship Structure:</h3>
 * <p>When Service B calls Service A's endpoint /A using RestTemplate/WebClient:</p>
 * <pre>
 * Endpoint B (Service B) -[MAKES_EXTERNAL_CALL]-> ExternalCall Node -[CALLS_ENDPOINT]-> Endpoint /A (Service A)
 * </pre>
 *
 * <p>Important notes:</p>
 * <ul>
 *   <li>MAKES_EXTERNAL_CALL: Created during graph building (Neo4jGraphBuilder) from source endpoint/method to ExternalCall</li>
 *   <li>CALLS_ENDPOINT: Created during resolution (this service) from ExternalCall to target endpoint via targetEndpointNode field</li>
 *   <li>The ExternalCall node stores metadata about the HTTP call (method, URL, client type)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncExternalCallResolverService {

    private final EndpointNodeRepository endpointNodeRepository;
    private final ExternalCallNodeRepository externalCallNodeRepository;
    private final KafkaTopicNodeRepository kafkaTopicNodeRepository;
    private final ExternalCallResolutionService resolutionService;

    /**
     * Asynchronously resolve all external calls for a project.
     * Links ExternalCall nodes to their target endpoints via the CALLS_ENDPOINT relationship.
     */
    @Async
    public void resolveProjectExternalCalls(String projectId, String userId) {
        try {
            log.info("Starting async resolution of external calls and Kafka topics for project: {}", projectId);

            // Get all endpoints from Neo4j graph for this project
            List<EndpointNode> endpoints = endpointNodeRepository.findByProjectId(projectId);

            if (endpoints.isEmpty()) {
                log.warn("No endpoints found in Neo4j graph for project: {}", projectId);
                return;
            }

            log.info("Found {} endpoints in graph for project: {}", endpoints.size(), projectId);

            // Build endpoint registry from graph
            List<EndpointRegistry> endpointRegistry = buildEndpointRegistryFromGraph(endpoints);
            log.info("Built endpoint registry with {} endpoints", endpointRegistry.size());

            // Build Kafka topic registry from graph
            List<KafkaTopicRegistry> kafkaTopicRegistry = buildKafkaTopicRegistryFromGraph(projectId);
            log.info("Built Kafka topic registry with {} topics", kafkaTopicRegistry.size());

            // Get all external call nodes that need resolution
            List<ExternalCallNode> externalCalls = externalCallNodeRepository.findByProjectId(projectId);
            log.info("Found {} external calls to resolve", externalCalls.size());

            // Resolve each external call and update in graph
            int resolvedCount = 0;
            for (ExternalCallNode externalCall : externalCalls) {
                ExternalCallInfo resolved = resolutionService.resolveExternalCall(
                        convertToExternalCallInfo(externalCall),
                        endpointRegistry
                );

                if (resolved.isResolved()) {
                    // Update the external call node with resolution info
                    externalCall.setResolved(true);
                    externalCall.setTargetService(resolved.getTargetService());
                    externalCall.setTargetEndpoint(resolved.getTargetEndpoint());
                    externalCall.setTargetControllerClass(resolved.getTargetControllerClass());
                    externalCall.setTargetHandlerMethod(resolved.getTargetHandlerMethod());

                    // Find the target endpoint ID (for relationship creation)
                    String targetEndpointId = null;
                    try {
                        if (resolved.getTargetEndpoint() != null && !resolved.getTargetEndpoint().isBlank()) {
                            Optional<EndpointNode> epOpt = endpointNodeRepository.findByFullPathAndMethod(
                                    projectId, resolved.getTargetEndpoint(), resolved.getHttpMethod());
                            if (epOpt.isPresent()) {
                                targetEndpointId = epOpt.get().getId();
                                log.debug("Found target endpoint: {} (id={})", epOpt.get().getFullPath(), targetEndpointId);
                            } else {
                                // Fallback: try pattern matching
                                for (EndpointNode candidate : endpoints) {
                                    if (candidate.getHttpMethod() != null && resolved.getHttpMethod() != null
                                            && !candidate.getHttpMethod().equalsIgnoreCase(resolved.getHttpMethod())) {
                                        continue;
                                    }
                                    String candidatePath = candidate.getFullPath();
                                    if (candidatePath == null) continue;

                                    if (candidatePath.equals(resolved.getTargetEndpoint())) {
                                        targetEndpointId = candidate.getId();
                                        log.debug("Found target endpoint (exact match): {} (id={})", candidatePath, targetEndpointId);
                                        break;
                                    }

                                    String pattern = generatePathPattern(candidatePath);
                                    String resolvedPath = resolved.getTargetEndpoint();
                                    if (resolvedPath.matches("^" + pattern + "(/.*)?$")) {
                                        targetEndpointId = candidate.getId();
                                        log.debug("Found target endpoint (pattern match): {} (id={})", candidatePath, targetEndpointId);
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Failed to find target endpoint for external call: {}", e.getMessage());
                    }

                    // IMPORTANT: Clear the targetEndpointNode before saving to prevent Spring Data Neo4j
                    // from re-persisting/modifying the target endpoint's relationships
                    externalCall.setTargetEndpointNode(null);

                    // Save the external call node with resolved properties (but no targetEndpointNode)
                    ExternalCallNode saved = externalCallNodeRepository.save(externalCall);
                    log.debug("Saved ExternalCallNode id={} url={} resolved={} targetEndpoint={}",
                            saved.getId(), saved.getUrl(), saved.isResolved(), saved.getTargetEndpoint());

                    // Create CALLS_ENDPOINT relationship separately if target endpoint was found
                    if (targetEndpointId != null) {
                        try {
                            externalCallNodeRepository.createCallsEndpointRelationship(saved.getId(), targetEndpointId);
                            log.info("Created CALLS_ENDPOINT relationship: externalCallId={} -> endpointId={}",
                                    saved.getId(), targetEndpointId);
                        } catch (Exception e) {
                            log.warn("Failed to create CALLS_ENDPOINT relationship: {}", e.getMessage());
                        }
                    }

                    resolvedCount++;

                    log.info("Resolved: {} {} -> {}:{}",
                            resolved.getHttpMethod(), resolved.getUrl(),
                            resolved.getTargetService(), resolved.getTargetEndpoint());
                } else {
                    log.debug("Could not resolve: {} {}",
                            externalCall.getHttpMethod(), externalCall.getUrl());
                }
            }

            log.info("Completed resolution for project: {}. Resolved {}/{} external calls",
                    projectId, resolvedCount, externalCalls.size());

        } catch (Exception e) {
            log.error("Error resolving external calls for project: {}", projectId, e);
        }
    }

    /**
     * Build registry of all endpoints from Neo4j graph
     */
    private List<EndpointRegistry> buildEndpointRegistryFromGraph(List<EndpointNode> endpoints) {
        return endpoints.stream()
                .map(endpoint -> EndpointRegistry.builder()
                        .serviceName(endpoint.getAppKey() != null ? extractServiceNameFromAppKey(endpoint.getAppKey()) : "unknown-service")
                        .applicationClass(endpoint.getAppKey())
                        .controllerClass(endpoint.getControllerClass())
                        .handlerMethod(endpoint.getHandlerMethod())
                        .httpMethod(endpoint.getHttpMethod())
                        .path(endpoint.getFullPath())
                        .pathPattern(generatePathPattern(endpoint.getFullPath()))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Convert ExternalCallNode to ExternalCallInfo for resolution
     */
    private ExternalCallInfo convertToExternalCallInfo(ExternalCallNode node) {
        return ExternalCallInfo.builder()
                .httpMethod(node.getHttpMethod())
                .url(node.getUrl())
                .clientType(node.getClientType())
                .targetClass(node.getTargetClass())
                .targetMethod(node.getTargetMethod())
                .resolved(node.isResolved())
                .targetService(node.getTargetService())
                .targetEndpoint(node.getTargetEndpoint())
                .targetControllerClass(node.getTargetControllerClass())
                .targetHandlerMethod(node.getTargetHandlerMethod())
                .build();
    }

    /**
     * Extract service name from appKey
     */
    private String extractServiceNameFromAppKey(String appKey) {
        if (appKey == null || appKey.isEmpty()) {
            return "unknown-service";
        }
        // AppKey format is usually: package.MainClass
        // Extract the last part before .MainClass
        String[] parts = appKey.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2];
        }
        return appKey;
    }

    /**
     * Build registry of all Kafka topics from Neo4j graph
     */
    private List<KafkaTopicRegistry> buildKafkaTopicRegistryFromGraph(String projectId) {
        List<KafkaTopicRegistry> registry = new ArrayList<>();

        // Get all Kafka topics for the project
        List<KafkaTopicNode> topics = kafkaTopicNodeRepository.findByProjectId(projectId);

        for (KafkaTopicNode topic : topics) {
            // Get producer details from the topic node
            if (topic.getProducerDetails() != null && !topic.getProducerDetails().isEmpty()) {
                for (String producerDetail : topic.getProducerDetails()) {
                    // ProducerDetail format: "ClassName.methodName"
                    String[] parts = producerDetail.split("\\.");
                    String className = parts.length > 1 ? parts[0] : producerDetail;
                    String methodName = parts.length > 1 ? parts[1] : "";

                    registry.add(KafkaTopicRegistry.builder()
                            .serviceName(extractServiceNameFromAppKey(topic.getAppKey()))
                            .applicationClass(topic.getAppKey())
                            .direction("PRODUCER")
                            .topic(topic.getName())
                            .className(className)
                            .methodName(methodName)
                            .clientType("KafkaTemplate")
                            .build());
                }
            }

            // Get consumer details from the topic node
            if (topic.getConsumerDetails() != null && !topic.getConsumerDetails().isEmpty()) {
                for (String consumerDetail : topic.getConsumerDetails()) {
                    // ConsumerDetail format: "ListenerClass.methodName"
                    String[] parts = consumerDetail.split("\\.");
                    String className = parts.length > 1 ? parts[0] : consumerDetail;
                    String methodName = parts.length > 1 ? parts[1] : "";

                    registry.add(KafkaTopicRegistry.builder()
                            .serviceName(extractServiceNameFromAppKey(topic.getAppKey()))
                            .applicationClass(topic.getAppKey())
                            .direction("CONSUMER")
                            .topic(topic.getName())
                            .className(className)
                            .methodName(methodName)
                            .clientType("KafkaListener")
                            .build());
                }
            }
        }

        return registry;
    }

    /**
     * Generate regex pattern from path
     */
    private String generatePathPattern(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }

        return path
                .replaceAll("\\{[^}]+\\}", "[^/]+")  // {id} -> [^/]+
                .replaceAll("<dynamic>", "[^/]*");    // <dynamic> -> [^/]*
    }
}
