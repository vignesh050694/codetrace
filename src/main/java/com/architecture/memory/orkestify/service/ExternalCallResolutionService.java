package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.EndpointRegistry;
import com.architecture.memory.orkestify.dto.ExternalCallInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ExternalCallResolutionService {

    /**
     * Build a registry of all endpoints across all analyzed services
     */
    public List<EndpointRegistry> buildEndpointRegistry(Map<String, List<EndpointRegistry>> serviceEndpoints) {
        log.info("Building endpoint registry from {} services", serviceEndpoints.size());

        return serviceEndpoints.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * Resolve an external call to its target endpoint
     */
    public ExternalCallInfo resolveExternalCall(
            ExternalCallInfo externalCall,
            List<EndpointRegistry> endpointRegistry) {

        String url = externalCall.getUrl();
        String httpMethod = externalCall.getHttpMethod();

        if (url == null || url.isEmpty() || "<dynamic>".equals(url)) {
            externalCall.setResolved(false);
            externalCall.setResolutionReason("URL is dynamic or empty, cannot resolve");
            return externalCall;
        }

        // Try to find matching endpoint
        Optional<EndpointRegistry> match = findMatchingEndpoint(url, httpMethod, endpointRegistry);

        if (match.isPresent()) {
            EndpointRegistry endpoint = match.get();
            externalCall.setResolved(true);
            externalCall.setTargetService(endpoint.getServiceName());
            externalCall.setTargetEndpoint(endpoint.getPath());
            externalCall.setTargetControllerClass(endpoint.getControllerClass());
            externalCall.setTargetHandlerMethod(endpoint.getHandlerMethod());
            externalCall.setResolutionReason("Matched pattern: " + endpoint.getPathPattern());
            log.debug("Resolved external call {} to {}/{}", url, endpoint.getServiceName(), endpoint.getPath());
        } else {
            externalCall.setResolved(false);
            externalCall.setResolutionReason("No matching endpoint found");
            log.debug("Could not resolve external call: {}", url);
        }

        return externalCall;
    }

    /**
     * Find endpoint matching the given URL and HTTP method
     */
    private Optional<EndpointRegistry> findMatchingEndpoint(
            String url,
            String httpMethod,
            List<EndpointRegistry> endpointRegistry) {

        String normalizedUrl = normalizeUrl(url);
        String normalizedMethod = normalizeHttpMethod(httpMethod);

        return endpointRegistry.stream()
                .filter(endpoint -> endpoint.getHttpMethod().equalsIgnoreCase(normalizedMethod))
                .filter(endpoint -> matchesPath(normalizedUrl, endpoint))
                .findFirst();
    }

    /**
     * Check if URL matches endpoint path (handles path parameters)
     */
    private boolean matchesPath(String urlToMatch, EndpointRegistry endpoint) {
        String endpointPath = endpoint.getPath();

        // Exact match
        if (urlToMatch.equals(endpointPath)) {
            return true;
        }

        // Pattern match (e.g., /api/users/<dynamic> matches /api/users/{id})
        String pathPattern = endpointPath
                .replaceAll("\\{[^}]+\\}", "[^/]+")  // Replace {param} with regex
                .replaceAll("<dynamic>", "[^/]*");   // Replace <dynamic> with regex

        return urlToMatch.matches("^" + pathPattern + "(/.*)?$");
    }

    /**
     * Normalize URL for matching (remove protocol, host, etc.)
     */
    private String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }

        // Remove protocol and host if present
        if (url.contains("://")) {
            url = url.substring(url.indexOf("://") + 3);
            url = url.substring(url.indexOf("/"));
        }

        // Remove query parameters
        if (url.contains("?")) {
            url = url.substring(0, url.indexOf("?"));
        }

        return url;
    }

    /**
     * Normalize HTTP method
     */
    private String normalizeHttpMethod(String method) {
        if (method == null) {
            return "REQUEST";
        }
        return method.toUpperCase(Locale.ROOT);
    }

    /**
     * Generate path pattern for matching
     */
    public String generatePathPattern(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }

        return path
                .replaceAll("\\{[^}]+\\}", "[^/]+")  // {id} -> [^/]+
                .replaceAll("<dynamic>", "[^/]*");    // <dynamic> -> [^/]*
    }
}
