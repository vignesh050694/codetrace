package com.architecture.memory.orkestify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointRegistry {
    private String serviceName;           // e.g., "user-service"
    private String applicationClass;      // main app class name
    private String controllerClass;       // e.g., "UserController"
    private String handlerMethod;         // e.g., "getUserById"
    private String httpMethod;            // GET, POST, etc.
    private String path;                  // e.g., "/api/users/{id}"
    private String pathPattern;           // Regex pattern for matching (e.g., /api/users/[^/]+)
}
