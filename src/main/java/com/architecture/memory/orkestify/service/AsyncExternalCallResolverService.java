package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.EndpointRegistry;
import com.architecture.memory.orkestify.dto.ExternalCallInfo;
import com.architecture.memory.orkestify.model.CodeAnalysisResult;
import com.architecture.memory.orkestify.repository.CodeAnalysisResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncExternalCallResolverService {

    private final CodeAnalysisResultRepository codeAnalysisResultRepository;
    private final ExternalCallResolutionService resolutionService;

    /**
     * Asynchronously resolve all external calls for a project
     */
    @Async
    public void resolveProjectExternalCalls(String projectId, String userId) {
        try {
            log.info("Starting async resolution of external calls for project: {}", projectId);

            // Get all analysis results for project
            List<CodeAnalysisResult> analysisResults =
                    codeAnalysisResultRepository.findByProjectIdAndUserId(projectId, userId);

            if (analysisResults.isEmpty()) {
                log.warn("No analysis results found for project: {}", projectId);
                return;
            }

            log.info("Found {} analysis results for project: {}", analysisResults.size(), projectId);

            // Build endpoint registry from all analyzed services
            List<EndpointRegistry> endpointRegistry = buildEndpointRegistry(analysisResults);
            log.info("Built endpoint registry with {} endpoints", endpointRegistry.size());

            // Resolve external calls for each result
            for (CodeAnalysisResult result : analysisResults) {
                resolveExternalCallsForResult(result, endpointRegistry);
                codeAnalysisResultRepository.save(result);
            }

            log.info("Completed resolution of external calls for project: {}", projectId);

        } catch (Exception e) {
            log.error("Error resolving external calls for project: {}", projectId, e);
        }
    }

    /**
     * Build registry of all endpoints from analysis results
     */
    private List<EndpointRegistry> buildEndpointRegistry(List<CodeAnalysisResult> analysisResults) {
        List<EndpointRegistry> registry = new ArrayList<>();

        for (CodeAnalysisResult result : analysisResults) {
            String serviceName = extractServiceName(result);
            String appClass = result.getApplicationInfo() != null ?
                    result.getApplicationInfo().getMainClassName() : "Unknown";

            // Add controller endpoints
            if (result.getControllers() != null) {
                for (var controller : result.getControllers()) {
                    if (controller.getEndpoints() != null) {
                        for (var endpoint : controller.getEndpoints()) {
                            registry.add(EndpointRegistry.builder()
                                    .serviceName(serviceName)
                                    .applicationClass(appClass)
                                    .controllerClass(controller.getClassName())
                                    .handlerMethod(endpoint.getHandlerMethod())
                                    .httpMethod(endpoint.getMethod())
                                    .path(endpoint.getPath())
                                    .pathPattern(generatePathPattern(endpoint.getPath()))
                                    .build());
                        }
                    }
                }
            }
        }

        return registry;
    }

    /**
     * Resolve external calls for a single analysis result
     */
    private void resolveExternalCallsForResult(CodeAnalysisResult result, List<EndpointRegistry> endpointRegistry) {
        if (result.getControllers() == null) {
            return;
        }

        for (var controller : result.getControllers()) {
            if (controller.getEndpoints() == null) {
                continue;
            }

            for (var endpoint : controller.getEndpoints()) {
                if (endpoint.getExternalCalls() == null) {
                    continue;
                }

                // Resolve each external call in place
                for (var externalCall : endpoint.getExternalCalls()) {
                    ExternalCallInfo resolved = resolutionService.resolveExternalCall(
                            externalCall,
                            endpointRegistry
                    );

                    if (resolved.isResolved()) {
                        log.info("Resolved: {} {} -> {}:{}",
                                resolved.getHttpMethod(), resolved.getUrl(),
                                resolved.getTargetService(), resolved.getTargetEndpoint());
                    } else {
                        log.debug("Could not resolve: {} {}",
                                resolved.getHttpMethod(), resolved.getUrl());
                    }
                }
            }
        }
    }

    /**
     * Extract service name from repository URL
     */
    private String extractServiceName(CodeAnalysisResult result) {
        String repoUrl = result.getRepoUrl();
        if (repoUrl == null || repoUrl.isEmpty()) {
            return "unknown-service";
        }

        // Extract repo name from URL
        String name = repoUrl.substring(repoUrl.lastIndexOf("/") + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
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
