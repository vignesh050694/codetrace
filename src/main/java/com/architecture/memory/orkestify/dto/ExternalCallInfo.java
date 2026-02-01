package com.architecture.memory.orkestify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalCallInfo {
    private String clientType; // RestTemplate, WebClient, Feign
    private String httpMethod; // GET, POST, PUT, DELETE, PATCH, REQUEST, UNKNOWN
    private String url;        // literal or <dynamic>
    private String targetClass;
    private String targetMethod;
    private LineRange line;

    // Resolved target information
    private String targetService;         // e.g., "user-service"
    private String targetEndpoint;        // e.g., "/api/users/{id}"
    private String targetControllerClass; // e.g., "UserController"
    private String targetHandlerMethod;   // e.g., "getUserById"
    private boolean resolved;             // Whether target was found
    private String resolutionReason;      // Why resolution succeeded/failed
}
