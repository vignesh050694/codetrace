package com.architecture.memory.orkestify.service.graph.analyzer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A detected Kafka producer call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedKafkaCall {
    private String direction; // PRODUCER
    private String topicName; // resolved or raw
    private String rawTopic;
    private String className;
    private String methodName;
    private int lineStart;
    private int lineEnd;
}
