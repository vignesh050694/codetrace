package com.architecture.memory.orkestify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KafkaProducerInfo {
    private String projectId;          // Project containing the producer
    private String repoUrl;            // Repository URL
    private String serviceName;        // Service name (from Spring Boot app or class)
    private String className;          // Class that produces the message
    private String methodName;         // Method that produces
    private String packageName;        // Package of the producer class
    private LineRange line;            // Where the producer call is
}
