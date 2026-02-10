package com.architecture.memory.orkestify.service.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Arrays;

/**
 * Centralized service for generating stable, deterministic Canonical IDs for graph nodes and edges.
 *
 * Canonical IDs are:
 * - Deterministic: Same code structure always produces the same ID
 * - Stable: Unchanged across commits, formatting changes, line number changes
 * - Independent: Not affected by UUIDs, file locations, or whitespace
 *
 * Format Rules:
 * - Controller: controller:{package}.{className}
 * - Endpoint: endpoint:{HTTP_METHOD}:{normalizedPath}
 * - Method: method:{fullyQualifiedClass}.{methodName}({paramTypes})
 * - ExternalCall: external:{httpMethod}:{normalizedResolvedPath}:resolved={true|false}
 * - Service: service:{fullyQualifiedClass}
 *
 * Edge Canonical IDs:
 * - Format: {edgeType}:{sourceCanonicalId}->{targetCanonicalId}
 */
@Service
@Slf4j
public class CanonicalIdGenerator {

    // Pattern for detecting path variables like {id}, {userId}, etc.
    private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile("\\{[^}]+\\}");

    // Pattern for detecting numeric IDs in paths
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("/\\d+(/|$)");

    // Pattern for detecting UUID-like patterns in paths
    private static final Pattern UUID_PATTERN = Pattern.compile("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(/|$)");

    /**
     * Generate canonical ID for a Controller node.
     * Format: controller:{package}.{className}
     */
    public String generateControllerCanonicalId(String packageName, String className) {
        if (packageName == null || className == null) {
            log.warn("Cannot generate canonical ID for controller with null package or class name");
            return "controller:unknown";
        }
        return String.format("controller:%s.%s", packageName, className);
    }

    /**
     * Generate canonical ID for an Endpoint node.
     * Format: endpoint:{HTTP_METHOD}:{normalizedPath}
     */
    public String generateEndpointCanonicalId(String httpMethod, String path) {
        if (httpMethod == null || path == null) {
            log.warn("Cannot generate canonical ID for endpoint with null method or path");
            return "endpoint:unknown";
        }
        String normalizedPath = normalizePath(path);
        return String.format("endpoint:%s:%s", httpMethod.toUpperCase(), normalizedPath);
    }

    /**
     * Generate canonical ID for a Method node.
     * Format: method:{fullyQualifiedClass}.{methodName}({paramTypes})
     */
    public String generateMethodCanonicalId(String packageName, String className, String methodName, String signature) {
        if (className == null || methodName == null) {
            log.warn("Cannot generate canonical ID for method with null class or method name");
            return "method:unknown";
        }

        String fullyQualifiedClass = packageName != null
            ? packageName + "." + className
            : className;

        String paramTypes = extractParameterTypes(signature);
        return String.format("method:%s.%s(%s)", fullyQualifiedClass, methodName, paramTypes);
    }

    /**
     * Generate canonical ID for an ExternalCall node.
     * Format: external:{httpMethod}:{normalizedResolvedPath}:resolved={true|false}
     */
    public String generateExternalCallCanonicalId(String httpMethod, String url, boolean resolved) {
        if (httpMethod == null || url == null) {
            log.warn("Cannot generate canonical ID for external call with null method or URL");
            return "external:unknown";
        }

        String normalizedUrl = normalizeExternalUrl(url);
        return String.format("external:%s:%s:resolved=%s",
            httpMethod.toUpperCase(),
            normalizedUrl,
            resolved);
    }

    /**
     * Generate canonical ID for a Service node.
     * Format: service:{fullyQualifiedClass}
     */
    public String generateServiceCanonicalId(String packageName, String className) {
        if (className == null) {
            log.warn("Cannot generate canonical ID for service with null class name");
            return "service:unknown";
        }

        String fullyQualifiedClass = packageName != null
            ? packageName + "." + className
            : className;

        return String.format("service:%s", fullyQualifiedClass);
    }

    /**
     * Generate canonical ID for a Repository node.
     * Format: repository:{fullyQualifiedClass}
     */
    public String generateRepositoryCanonicalId(String packageName, String className) {
        if (className == null) {
            log.warn("Cannot generate canonical ID for repository with null class name");
            return "repository:unknown";
        }

        String fullyQualifiedClass = packageName != null
            ? packageName + "." + className
            : className;

        return String.format("repository:%s", fullyQualifiedClass);
    }

    /**
     * Generate canonical ID for an Application node.
     * Format: application:{appKey}
     */
    public String generateApplicationCanonicalId(String appKey) {
        if (appKey == null) {
            log.warn("Cannot generate canonical ID for application with null appKey");
            return "application:unknown";
        }
        return String.format("application:%s", appKey);
    }

    /**
     * Generate canonical ID for a KafkaTopic node.
     * Format: kafka_topic:{topicName}
     */
    public String generateKafkaTopicCanonicalId(String topicName) {
        if (topicName == null) {
            log.warn("Cannot generate canonical ID for Kafka topic with null name");
            return "kafka_topic:unknown";
        }
        return String.format("kafka_topic:%s", topicName);
    }

    /**
     * Generate canonical ID for a DatabaseTable node.
     * Format: database_table:{tableName}
     */
    public String generateDatabaseTableCanonicalId(String tableName) {
        if (tableName == null) {
            log.warn("Cannot generate canonical ID for database table with null name");
            return "database_table:unknown";
        }
        return String.format("database_table:%s", tableName.toLowerCase());
    }

    /**
     * Generate canonical ID for an edge.
     * Format: {edgeType}:{sourceCanonicalId}->{targetCanonicalId}
     */
    public String generateEdgeCanonicalId(String edgeType, String sourceCanonicalId, String targetCanonicalId) {
        if (edgeType == null || sourceCanonicalId == null || targetCanonicalId == null) {
            log.warn("Cannot generate canonical ID for edge with null type or node IDs");
            return "edge:unknown";
        }
        return String.format("%s:%s->%s", edgeType.toLowerCase(), sourceCanonicalId, targetCanonicalId);
    }

    /**
     * Normalize a path by replacing variable segments with {*}.
     * Examples:
     * - /api/users/{id} -> /api/users/{*}
     * - /api/users/123 -> /api/users/{*}
     * - /api/orders/{orderId}/items/{itemId} -> /api/orders/{*}/items/{*}
     */
    public String normalizePath(String path) {
        if (path == null) {
            return "";
        }

        String normalized = path;

        // Replace existing path variables with {*}
        normalized = PATH_VARIABLE_PATTERN.matcher(normalized).replaceAll("{*}");

        // Replace numeric IDs with {*}
        normalized = NUMERIC_ID_PATTERN.matcher(normalized).replaceAll("/{*}$1");

        // Replace UUID patterns with {*}
        normalized = UUID_PATTERN.matcher(normalized).replaceAll("/{*}$1");

        // Remove trailing slash for consistency
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    /**
     * Normalize an external URL by extracting the path and normalizing it.
     * Removes protocol, host, port, and query parameters.
     * Examples:
     * - http://localhost:8080/api/users/123 -> /api/users/{*}
     * - https://api.example.com/v1/orders?status=active -> /v1/orders
     */
    public String normalizeExternalUrl(String url) {
        if (url == null) {
            return "";
        }

        // Handle dynamic placeholder already in URL
        if (url.contains("<dynamic>")) {
            // Extract just the path portion with placeholders preserved
            String path = extractPathFromUrl(url);
            // Replace <dynamic> with {*}
            return path.replaceAll("<dynamic>", "{*}");
        }

        // Extract path from URL
        String path = extractPathFromUrl(url);

        // Normalize the path
        return normalizePath(path);
    }

    /**
     * Extract path from a full URL.
     * Examples:
     * - http://localhost:8080/api/users -> /api/users
     * - https://api.example.com/v1/orders?status=active -> /v1/orders
     * - /api/users (already a path) -> /api/users
     */
    private String extractPathFromUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            // Remove protocol
            String withoutProtocol = url.replaceFirst("^https?://", "");

            // Find first slash (start of path)
            int pathStart = withoutProtocol.indexOf('/');
            if (pathStart == -1) {
                return "/";
            }

            String path = withoutProtocol.substring(pathStart);

            // Remove query parameters
            int queryStart = path.indexOf('?');
            if (queryStart != -1) {
                path = path.substring(0, queryStart);
            }

            return path;
        }

        // Already a path, just remove query parameters
        int queryStart = url.indexOf('?');
        if (queryStart != -1) {
            return url.substring(0, queryStart);
        }

        return url;
    }

    /**
     * Extract parameter types from a method signature.
     * Ignores parameter names, keeps only types.
     * Examples:
     * - "method(String name, int age)" -> "String,int"
     * - "method()" -> ""
     * - "method(List<String> items)" -> "List<String>"
     */
    private String extractParameterTypes(String signature) {
        if (signature == null || !signature.contains("(")) {
            return "";
        }

        // Extract the parameter part between parentheses
        int start = signature.indexOf('(');
        int end = signature.lastIndexOf(')');
        if (start == -1 || end == -1 || start >= end) {
            return "";
        }

        String params = signature.substring(start + 1, end).trim();
        if (params.isEmpty()) {
            return "";
        }

        // Split by comma and extract types
        return Arrays.stream(params.split(","))
            .map(String::trim)
            .map(this::extractType)
            .collect(Collectors.joining(","));
    }

    /**
     * Extract type from a parameter string.
     * Examples:
     * - "String name" -> "String"
     * - "List<String> items" -> "List<String>"
     * - "int age" -> "int"
     */
    private String extractType(String param) {
        if (param == null || param.isEmpty()) {
            return "";
        }

        // Find the last space before the parameter name
        // Handle generics: "List<String> items" should return "List<String>"
        int lastSpace = param.lastIndexOf(' ');
        if (lastSpace == -1) {
            return param; // No space, assume it's just a type
        }

        return param.substring(0, lastSpace).trim();
    }
}

