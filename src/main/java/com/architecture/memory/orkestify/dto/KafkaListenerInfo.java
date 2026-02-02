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
public class KafkaListenerInfo {
    private String className;          // e.g., "MarkEventConsumer"
    private String packageName;        // e.g., "com.demo.aggregator.consumer"
    private LineRange line;            // Class line range
    private List<KafkaListenerMethod> listeners; // Individual @KafkaListener methods
}
