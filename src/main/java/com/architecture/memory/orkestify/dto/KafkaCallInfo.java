package com.architecture.memory.orkestify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KafkaCallInfo {
    private String direction;           // PRODUCER or CONSUMER
    private String topic;               // Kafka topic name
    private String clientType;          // KafkaTemplate, ReactiveKafkaProducerTemplate, @KafkaListener, etc.
    private String methodName;          // Method that produces/consumes
    private LineRange line;

    // For PRODUCER: Resolved target information (which services consume this topic)
    private String targetService;       // Service that consumes this topic
    private String targetConsumerClass; // Consumer class name
    private String targetConsumerMethod;// Consumer method name

    // For CONSUMER: Resolved source information (which services produce to this topic)
    private List<KafkaProducerInfo> producers;  // List of producers for this topic

    // Resolution status
    private boolean resolved;           // Whether producer/consumer was found
    private String resolutionReason;    // Why resolution succeeded/failed
}
