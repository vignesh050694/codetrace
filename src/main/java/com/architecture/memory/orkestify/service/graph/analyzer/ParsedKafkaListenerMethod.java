package com.architecture.memory.orkestify.service.graph.analyzer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A @KafkaListener annotated method found during parsing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedKafkaListenerMethod {
    private String methodName;
    private String signature;
    private String topic;
    private String groupId;
    private int lineStart;
    private int lineEnd;

    @Builder.Default
    private List<RawInvocation> rawInvocations = new ArrayList<>();

    @Builder.Default
    private List<ParsedExternalCall> externalCalls = new ArrayList<>();

    @Builder.Default
    private List<ParsedKafkaCall> kafkaCalls = new ArrayList<>();
}
