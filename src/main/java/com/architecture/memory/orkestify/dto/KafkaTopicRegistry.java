package com.architecture.memory.orkestify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KafkaTopicRegistry {
    private String serviceName;           // e.g., "user-service"
    private String applicationClass;      // main app class name
    private String direction;             // PRODUCER or CONSUMER
    private String topic;                 // Kafka topic name
    private String className;             // Producer/Consumer class name
    private String methodName;            // Method that produces/consumes
    private String clientType;            // KafkaTemplate, @KafkaListener, etc.
}
