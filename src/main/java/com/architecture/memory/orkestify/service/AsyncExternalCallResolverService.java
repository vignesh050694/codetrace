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

@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncExternalCallResolverService {

    private final EndpointNodeRepository endpointNodeRepository;
    private final ExternalCallNodeRepository externalCallNodeRepository;
    private final KafkaTopicNodeRepository kafkaTopicNodeRepository;
    private final ExternalCallResolutionService resolutionService;

    /**
     * Asynchronously resolve all external calls for a project
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

                    EndpointNode matchedEndpointNode = null;
                    // Try to find and assign the endpoint node
                    try {
                        if (resolved.getTargetEndpoint() != null && !resolved.getTargetEndpoint().isBlank()) {
                            Optional<EndpointNode> epOpt = endpointNodeRepository.findByFullPathAndMethod(
                                    projectId, resolved.getTargetEndpoint(), resolved.getHttpMethod());
                            if (epOpt.isPresent()) {
                                matchedEndpointNode = epOpt.get();
                                externalCall.setTargetEndpointNode(matchedEndpointNode);
                            } else {
                                for (EndpointNode candidate : endpoints) {
                                    if (candidate.getHttpMethod() != null && resolved.getHttpMethod() != null
                                            && !candidate.getHttpMethod().equalsIgnoreCase(resolved.getHttpMethod())) {
                                        continue;
                                    }
                                    String candidatePath = candidate.getFullPath();
                                    if (candidatePath == null) continue;

                                    if (candidatePath.equals(resolved.getTargetEndpoint())) {
                                        matchedEndpointNode = candidate;
                                        externalCall.setTargetEndpointNode(candidate);
                                        break;
                                    }

                                    String pattern = generatePathPattern(candidatePath);
                                    String resolvedPath = resolved.getTargetEndpoint();
                                    if (resolvedPath.matches("^" + pattern + "(/.*)?$")) {
                                        matchedEndpointNode = candidate;
                                        externalCall.setTargetEndpointNode(candidate);
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Failed to link external call to endpoint node: {}", e.getMessage());
                    }

                    // Save the external call node first to ensure it has an ID
                    ExternalCallNode saved = externalCallNodeRepository.save(externalCall);
                    log.debug("Saved ExternalCallNode id={} url={} resolved={}", saved.getId(), saved.getUrl(), saved.isResolved());

                    // If we matched an endpoint, ensure there is an explicit relationship in Neo4j
                    try {
                        if (matchedEndpointNode != null && saved.getId() != null) {
                            endpointNodeRepository.createMakesExternalCallRel(matchedEndpointNode.getId(), saved.getId());
                            log.info("Created MAKES_EXTERNAL_CALL relationship: endpointId={} -> externalCallId={}", matchedEndpointNode.getId(), saved.getId());
                        }
                    } catch (Exception e) {
                        log.debug("Failed to create MAKES_EXTERNAL_CALL relationship: {}", e.getMessage());
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
