package com.architecture.memory.orkestify.service.graph.analyzer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A detected external HTTP call (RestTemplate, WebClient, Feign).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedExternalCall {
    private String clientType; // RestTemplate, WebClient, Feign
    private String httpMethod;
    private String url;
    private String targetClass;
    private String targetMethod;
    private int lineStart;
    private int lineEnd;
}
