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
public class EndpointInfo {
    private String method;
    private String path;
    private String handlerMethod;
    private LineRange line;
    private String signature;
    private List<MethodCall> calls;
    private List<ExternalCallInfo> externalCalls;
    private RequestBodyInfo requestBody;
    private ResponseTypeInfo response;
}
