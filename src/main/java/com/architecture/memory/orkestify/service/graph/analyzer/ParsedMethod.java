package com.architecture.memory.orkestify.service.graph.analyzer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Intermediate representation of a parsed method.
 * Captures raw invocations (unresolved) that get resolved in Pass 2.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedMethod {
    private String methodName;
    private String signature;
    private int lineStart;
    private int lineEnd;
    private boolean isPublic;
    private boolean isPrivate;

    // For endpoint methods (controller)
    private String httpMethod;
    private String path;
    private String requestBodyType;
    private String responseType;

    // Raw invocations found in the method body (unresolved in Pass 1)
    @Builder.Default
    private List<RawInvocation> rawInvocations = new ArrayList<>();

    // External HTTP calls detected (RestTemplate, WebClient, Feign)
    @Builder.Default
    private List<ParsedExternalCall> externalCalls = new ArrayList<>();

    // Kafka producer calls detected
    @Builder.Default
    private List<ParsedKafkaCall> kafkaCalls = new ArrayList<>();
}
