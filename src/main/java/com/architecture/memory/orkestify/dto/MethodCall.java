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
public class MethodCall {
    private String className;
    private LineRange line;
    private String signature;
    private String handlerMethod;
    private List<MethodCall> calls;
    private List<ExternalCallInfo> externalCalls;
    private List<KafkaCallInfo> kafkaCalls;
}
