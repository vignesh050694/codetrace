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
public class KafkaListenerMethod {
    private String methodName;         // e.g., "consumeMarkCreated"
    private String topic;              // Resolved topic name
    private String groupId;            // Consumer group ID
    private LineRange line;            // Method line range
    private String signature;          // Method signature
    private List<MethodCall> calls;    // Methods called from this listener
    private List<ExternalCallInfo> externalCalls; // REST calls made from listener
    private List<KafkaCallInfo> kafkaCalls;       // Kafka calls (producer info is in here for CONSUMER direction)
}
